package com.bookmap.plugin.rong.orderwall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tracks large liquidity walls and retains meaningful size changes at each level.
 */
public class OrderWallLabelTracker {

    private static final int GROWTH_PHASE_SPLIT_MULTIPLIER = 2;
    private static final long DECREASE_STABILITY_MS = 1_000L;
    private static final long LABEL_MIN_LIFETIME_MS = 1_000L;
    private static final long NS_PER_MS = 1_000_000L;

    private final String instrumentAlias;
    private final double pips;
    private final OrderWallLabelStore store;
    private final int minimumSize;
    private final long decreaseStabilityMs;
    private final long labelMinLifetimeMs;
    private final Runnable labelChangeListener;
    private final ScheduledExecutorService scheduler;
    private final Map<LevelKey, Integer> currentDisplaySizes = new HashMap<>();
    private final Map<LevelKey, PendingLabel> pendingLabels = new HashMap<>();
    private final Map<LevelKey, PendingDecrease> pendingDecreases = new HashMap<>();
    private boolean shutdown;

    public OrderWallLabelTracker(String instrumentAlias, double pips, OrderWallLabelStore store,
                                 int minimumSize, int retainDistanceTicks) {
        this(instrumentAlias, pips, store, minimumSize, retainDistanceTicks, DECREASE_STABILITY_MS, null);
    }

    public OrderWallLabelTracker(String instrumentAlias, double pips, OrderWallLabelStore store,
                                 int minimumSize, int retainDistanceTicks, Runnable labelChangeListener) {
        this(instrumentAlias, pips, store, minimumSize, retainDistanceTicks,
                DECREASE_STABILITY_MS, labelChangeListener);
    }

    OrderWallLabelTracker(String instrumentAlias, double pips, OrderWallLabelStore store,
                          int minimumSize, int retainDistanceTicks, long decreaseStabilityMs,
                          Runnable labelChangeListener) {
        this(instrumentAlias, pips, store, minimumSize, retainDistanceTicks,
                decreaseStabilityMs, LABEL_MIN_LIFETIME_MS, labelChangeListener);
    }

    OrderWallLabelTracker(String instrumentAlias, double pips, OrderWallLabelStore store,
                          int minimumSize, int retainDistanceTicks, long decreaseStabilityMs,
                          long labelMinLifetimeMs, Runnable labelChangeListener) {
        this.instrumentAlias = instrumentAlias;
        this.pips = pips;
        this.store = store;
        this.minimumSize = minimumSize;
        this.decreaseStabilityMs = decreaseStabilityMs;
        this.labelMinLifetimeMs = labelMinLifetimeMs;
        this.labelChangeListener = labelChangeListener;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wall-label-decrease-" + instrumentAlias);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Labels start as pending once a level exceeds the configured minimum size.
     * They become drawable only after surviving the configured minimum lifetime.
     * When a mature level falls back below the threshold, the segment is frozen
     * as a historical wall so the peak size remains visible over the bright section.
     */
    public synchronized boolean onDepth(boolean isBid, int priceTick, int size, long eventTimeNs) {
        if (shutdown) {
            return false;
        }
        LevelKey key = new LevelKey(isBid, priceTick);
        updateCurrentDisplaySize(key, size);

        OrderWallLabel existing = store.getActiveLabel(instrumentAlias, isBid, priceTick);
        boolean qualifiesNow = size > minimumSize;
        long timestampNs = eventTimeNs > 0 ? eventTimeNs : System.currentTimeMillis() * 1_000_000L;

        if (existing == null) {
            pendingDecreases.remove(key);
            if (!qualifiesNow) {
                pendingLabels.remove(key);
                return false;
            }
            if (updatePendingLabel(key, isBid, priceTick, size, timestampNs)) {
                pendingLabels.remove(key);
                return true;
            }
            return false;
        }

        pendingLabels.remove(key);

        if (!existing.isActive() && !qualifiesNow) {
            return false;
        }

        // Peak size is monotonic for a wall segment: once we have seen a larger wall,
        // never overwrite that historical maximum with a smaller reloaded wall.
        int peakSize = Math.max(existing.getPeakSize(), size);
        int currentSize = qualifiesNow ? size : 0;
        if (qualifiesNow && shouldStartNewGrowthPhase(existing, size)) {
            pendingDecreases.remove(key);
            freezeExistingLabel(existing, timestampNs);
            store.putLabel(new OrderWallLabel(
                    instrumentAlias,
                    isBid,
                    priceTick,
                    priceTick * pips,
                    size,
                    size,
                    timestampNs,
                    timestampNs,
                    System.currentTimeMillis()));
            return true;
        }

        List<Integer> sizePath = appendImmediateSizePath(existing.getSizePath(), size);
        updatePendingDecrease(key, existing.getId(), sizePath, size);
        long startTimeNs = existing.isActive() ? existing.getStartTimeNs() : timestampNs;
        long endTimeNs = timestampNs;

        if (existing.getCurrentSize() == currentSize
                && existing.getPeakSize() == peakSize
                && existing.getSizePath().equals(sizePath)
                && existing.getEndTimeNs() == endTimeNs
                && existing.getStartTimeNs() == startTimeNs) {
            return false;
        }

        OrderWallLabel updated = new OrderWallLabel(
                existing.getId(),
                instrumentAlias,
                isBid,
                priceTick,
                priceTick * pips,
                currentSize,
                peakSize,
                startTimeNs,
                endTimeNs,
                System.currentTimeMillis(),
                existing.hasBeenDisplayed(),
                sizePath);
        store.putLabel(updated);
        return true;
    }

    public synchronized boolean onTimestamp(long timestampNs) {
        if (shutdown || timestampNs <= 0 || pendingLabels.isEmpty()) {
            return false;
        }

        boolean changed = false;
        List<Map.Entry<LevelKey, PendingLabel>> candidates =
                new ArrayList<>(pendingLabels.entrySet());
        for (Map.Entry<LevelKey, PendingLabel> entry : candidates) {
            LevelKey key = entry.getKey();
            PendingLabel pending = entry.getValue();
            if (pendingLabels.get(key) != pending || !isPendingLabelMature(pending, timestampNs)) {
                continue;
            }
            pending.latestEventTimeNs = timestampNs;
            if (promotePendingLabel(key, pending)) {
                changed = true;
            }
        }
        return changed;
    }

    private boolean updatePendingLabel(LevelKey key, boolean isBid, int priceTick, int size, long timestampNs) {
        PendingLabel pending = pendingLabels.get(key);
        if (pending == null) {
            pending = new PendingLabel(isBid, priceTick, size, timestampNs);
            pendingLabels.put(key, pending);
        } else {
            pending.currentSize = size;
            pending.peakSize = Math.max(pending.peakSize, size);
            pending.latestEventTimeNs = timestampNs;
            pending.sizePath = appendImmediateSizePath(pending.sizePath, size);
        }

        if (!isPendingLabelMature(pending, timestampNs)) {
            return false;
        }
        return promotePendingLabel(key, pending);
    }

    private boolean isPendingLabelMature(PendingLabel pending, long timestampNs) {
        return labelMinLifetimeMs <= 0
                || timestampNs - pending.startTimeNs >= labelMinLifetimeMs * NS_PER_MS;
    }

    private boolean promotePendingLabel(LevelKey key, PendingLabel pending) {
        if (pendingLabels.remove(key, pending)) {
            store.putLabel(new OrderWallLabel(
                    UUID.randomUUID().toString(),
                    instrumentAlias,
                    pending.bid,
                    pending.priceTick,
                    pending.priceTick * pips,
                    pending.currentSize,
                    pending.peakSize,
                    pending.startTimeNs,
                    pending.latestEventTimeNs,
                    System.currentTimeMillis(),
                    false,
                    pending.sizePath));
            return true;
        }
        return false;
    }

    private boolean shouldStartNewGrowthPhase(OrderWallLabel existing, int rawSize) {
        int displaySize = OrderWallLabel.toDisplaySize(rawSize);
        List<Integer> sizePath = existing.getSizePath();
        if (displaySize <= 0 || sizePath.isEmpty()) {
            return false;
        }
        int firstDisplaySize = sizePath.get(0);
        int lastDisplaySize = sizePath.get(sizePath.size() - 1);
        return displaySize > lastDisplaySize
                && displaySize >= firstDisplaySize * GROWTH_PHASE_SPLIT_MULTIPLIER;
    }

    private void freezeExistingLabel(OrderWallLabel existing, long timestampNs) {
        OrderWallLabel frozen = new OrderWallLabel(
                existing.getId(),
                instrumentAlias,
                existing.isBid(),
                existing.getPriceTick(),
                existing.getRealPrice(),
                0,
                existing.getPeakSize(),
                existing.getStartTimeNs(),
                timestampNs,
                System.currentTimeMillis(),
                existing.hasBeenDisplayed(),
                existing.getSizePath());
        store.putLabel(frozen);
    }

    private List<Integer> appendImmediateSizePath(List<Integer> existingPath, int rawSize) {
        int displaySize = OrderWallLabel.toDisplaySize(rawSize);
        List<Integer> sizePath = new ArrayList<>(existingPath);
        if (rawSize == 0) {
            if (!sizePath.isEmpty() && sizePath.get(sizePath.size() - 1) != 0) {
                sizePath.add(0);
            }
            return sizePath;
        }
        if (displaySize <= 0) {
            return sizePath;
        }
        if (sizePath.isEmpty() || isNewGrowthPeak(sizePath, displaySize)) {
            sizePath.add(displaySize);
        }
        return sizePath;
    }

    private boolean isNewGrowthPeak(List<Integer> sizePath, int displaySize) {
        int peakDisplaySize = sizePath.stream().mapToInt(Integer::intValue).max().orElse(0);
        return displaySize > peakDisplaySize;
    }

    private void updatePendingDecrease(LevelKey key, String labelId, List<Integer> sizePath, int rawSize) {
        int displaySize = OrderWallLabel.toDisplaySize(rawSize);
        if (!shouldRecordStableDecrease(sizePath, displaySize)) {
            pendingDecreases.remove(key);
            return;
        }

        PendingDecrease pending = pendingDecreases.get(key);
        if (pending != null
                && pending.labelId.equals(labelId)
                && pending.displaySize == displaySize) {
            return;
        }

        pendingDecreases.put(key, new PendingDecrease(labelId, displaySize));
        scheduler.schedule(() -> evaluatePendingDecrease(key, labelId, displaySize),
                decreaseStabilityMs, TimeUnit.MILLISECONDS);
    }

    private void evaluatePendingDecrease(LevelKey key, String labelId, int displaySize) {
        boolean changed;
        synchronized (this) {
            changed = commitPendingDecrease(key, labelId, displaySize);
        }
        if (changed && labelChangeListener != null) {
            labelChangeListener.run();
        }
    }

    private boolean commitPendingDecrease(LevelKey key, String labelId, int displaySize) {
        if (shutdown) {
            return false;
        }

        PendingDecrease pending = pendingDecreases.get(key);
        if (pending == null
                || !pending.labelId.equals(labelId)
                || pending.displaySize != displaySize) {
            return false;
        }

        pendingDecreases.remove(key);
        Integer currentDisplaySize = currentDisplaySizes.get(key);
        if (currentDisplaySize == null || currentDisplaySize != displaySize) {
            return false;
        }

        OrderWallLabel label = store.getLabel(instrumentAlias, labelId);
        if (label == null
                || label.isBid() != key.bid
                || label.getPriceTick() != key.priceTick) {
            return false;
        }

        List<Integer> sizePath = new ArrayList<>(label.getSizePath());
        if (!shouldRecordStableDecrease(sizePath, displaySize)) {
            return false;
        }
        sizePath.add(displaySize);
        store.putLabel(new OrderWallLabel(
                label.getId(),
                label.getInstrumentAlias(),
                label.isBid(),
                label.getPriceTick(),
                label.getRealPrice(),
                label.getCurrentSize(),
                label.getPeakSize(),
                label.getStartTimeNs(),
                label.getEndTimeNs(),
                System.currentTimeMillis(),
                label.hasBeenDisplayed(),
                sizePath));
        return true;
    }

    private void updateCurrentDisplaySize(LevelKey key, int rawSize) {
        if (rawSize <= 0) {
            currentDisplaySizes.remove(key);
            return;
        }
        currentDisplaySizes.put(key, OrderWallLabel.toDisplaySize(rawSize));
    }

    private boolean shouldRecordStableDecrease(List<Integer> sizePath, int displaySize) {
        return displaySize > 0
                && !sizePath.isEmpty()
                && displaySize < sizePath.get(sizePath.size() - 1);
    }

    /**
     * Historical labels persist for the lifetime of the addon session.
     */
    public boolean cleanup(int currentPriceTick) {
        return false;
    }

    public synchronized void shutdown() {
        shutdown = true;
        pendingLabels.clear();
        pendingDecreases.clear();
        currentDisplaySizes.clear();
        scheduler.shutdownNow();
    }

    private static class PendingLabel {
        private final boolean bid;
        private final int priceTick;
        private final long startTimeNs;
        private int currentSize;
        private int peakSize;
        private long latestEventTimeNs;
        private List<Integer> sizePath;

        private PendingLabel(boolean bid, int priceTick, int size, long timestampNs) {
            this.bid = bid;
            this.priceTick = priceTick;
            this.startTimeNs = timestampNs;
            this.currentSize = size;
            this.peakSize = size;
            this.latestEventTimeNs = timestampNs;
            this.sizePath = appendInitialSizePath(size);
        }

        private static List<Integer> appendInitialSizePath(int size) {
            List<Integer> path = new ArrayList<>();
            int displaySize = OrderWallLabel.toDisplaySize(size);
            if (displaySize > 0) {
                path.add(displaySize);
            }
            return path;
        }
    }

    private static class PendingDecrease {
        private final String labelId;
        private final int displaySize;

        private PendingDecrease(String labelId, int displaySize) {
            this.labelId = labelId;
            this.displaySize = displaySize;
        }
    }

    private static class LevelKey {
        private final boolean bid;
        private final int priceTick;

        private LevelKey(boolean bid, int priceTick) {
            this.bid = bid;
            this.priceTick = priceTick;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof LevelKey)) {
                return false;
            }
            LevelKey levelKey = (LevelKey) o;
            return bid == levelKey.bid && priceTick == levelKey.priceTick;
        }

        @Override
        public int hashCode() {
            return Objects.hash(bid, priceTick);
        }
    }
}
