package com.bookmap.plugin.rong.orderwall;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.bookmap.plugin.rong.PluginLog;

import velox.api.layer1.data.TradeInfo;

/**
 * Detects large submitted/pulled order changes while suppressing decreases that
 * are mostly explained by trades at the same price.
 */
public class OrderWallChangeTracker {

    private static final long TRADE_LOOKBACK_MS = 400;
    private static final long TRADE_RETENTION_MS = 5_000;
    private static final double TRADE_EXPLAINED_RATIO = 0.70;
    private static final long ALERT_COOLDOWN_MS = 2_500;
    private static final long MIN_LARGE_ORDER_LIFETIME_MS = 500;

    private final String instrumentAlias;
    private final double pips;
    private final int largeOrderThreshold;
    private final double remainingRatio;
    private final long decreaseDecisionDelayMs;
    private final long minLargeOrderLifetimeMs;
    private final Consumer<OrderWallChangeEvent> alertConsumer;
    private final ScheduledExecutorService scheduler;
    private final Map<LevelKey, Integer> currentSizes = new HashMap<>();
    private final Map<LevelKey, PendingAdd> pendingAdds = new HashMap<>();
    private final Map<LevelKey, PendingDecrease> pendingDecreases = new HashMap<>();
    private final Map<LevelKey, Long> largeSinceMsByLevel = new HashMap<>();
    private final Map<LevelKey, Long> lastAlertMsByLevel = new HashMap<>();
    private final Deque<TradeRecord> recentTrades = new ArrayDeque<>();

    private boolean ready;
    private boolean shutdown;

    public OrderWallChangeTracker(String instrumentAlias, double pips, int largeOrderThreshold,
                                  double remainingRatio, long decreaseDecisionDelayMs,
                                  Consumer<OrderWallChangeEvent> alertConsumer) {
        this(instrumentAlias, pips, largeOrderThreshold, remainingRatio, decreaseDecisionDelayMs,
                MIN_LARGE_ORDER_LIFETIME_MS, alertConsumer);
    }

    OrderWallChangeTracker(String instrumentAlias, double pips, int largeOrderThreshold,
                           double remainingRatio, long decreaseDecisionDelayMs,
                           long minLargeOrderLifetimeMs,
                           Consumer<OrderWallChangeEvent> alertConsumer) {
        this.instrumentAlias = instrumentAlias;
        this.pips = pips;
        this.largeOrderThreshold = largeOrderThreshold;
        this.remainingRatio = remainingRatio;
        this.decreaseDecisionDelayMs = decreaseDecisionDelayMs;
        this.minLargeOrderLifetimeMs = minLargeOrderLifetimeMs;
        this.alertConsumer = alertConsumer;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wall-change-alert-" + instrumentAlias);
            t.setDaemon(true);
            return t;
        });
    }

    public synchronized void onDepth(boolean isBid, int priceTick, int size, long eventTimeNs) {
        if (shutdown) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        cleanupRecentTrades(nowMs);

        LevelKey key = new LevelKey(isBid, priceTick);
        int previousSize = currentSizes.getOrDefault(key, 0);
        if (ready) {
            promotePendingAddIfMature(key, previousSize, nowMs);
        }
        PendingAdd existingPendingAdd = pendingAdds.get(key);
        boolean wasLargeOrderEligible = isLargeOrderEligible(key, nowMs);

        if (size == 0) {
            currentSizes.remove(key);
        } else {
            currentSizes.put(key, size);
        }

        PendingDecrease existingPending = pendingDecreases.get(key);
        if (existingPending != null) {
            existingPending.latestSize = size;
            existingPending.latestEventTimeNs = eventTimeNs;
        }

        if (!ready || previousSize == size) {
            return;
        }

        if (existingPendingAdd != null) {
            if (size >= largeOrderThreshold) {
                existingPendingAdd.latestSize = size;
                existingPendingAdd.latestEventTimeNs = eventTimeNs;
            } else {
                pendingAdds.remove(key);
                largeSinceMsByLevel.remove(key);
                PluginLog.info("[WallChange] Suppressed flash add for " + instrumentAlias + " "
                        + (key.bid ? "BID" : "ASK") + " " + priceText(key.priceTick)
                        + " lifetime=" + (nowMs - existingPendingAdd.createdAtMs) + "ms");
            }
            return;
        }

        if (previousSize < largeOrderThreshold && size >= largeOrderThreshold) {
            schedulePendingAdd(key, previousSize, size, eventTimeNs, nowMs);
            return;
        }

        if (size < largeOrderThreshold) {
            largeSinceMsByLevel.remove(key);
        }

        if (previousSize >= largeOrderThreshold
                && size < previousSize
                && isSignificantDecrease(previousSize, size)) {
            if (!wasLargeOrderEligible) {
                return;
            }
            PendingDecrease pending = existingPending;
            if (pending == null) {
                pending = new PendingDecrease(key, previousSize, size, eventTimeNs, nowMs);
                pendingDecreases.put(key, pending);
                scheduler.schedule(() -> evaluatePendingDecrease(key),
                        decreaseDecisionDelayMs, TimeUnit.MILLISECONDS);
            } else {
                pending.latestSize = size;
                pending.latestEventTimeNs = eventTimeNs;
            }
        }
    }

    public synchronized void onTrade(int priceTick, int size, TradeInfo tradeInfo) {
        if (shutdown || size <= 0 || tradeInfo == null) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        recentTrades.addLast(new TradeRecord(priceTick, size, tradeInfo.isBidAggressor, nowMs));
        cleanupRecentTrades(nowMs);
    }

    public synchronized void markReady() {
        if (ready) {
            return;
        }
        ready = true;
        long nowMs = System.currentTimeMillis();
        pendingAdds.clear();
        pendingDecreases.clear();
        largeSinceMsByLevel.clear();
        for (Map.Entry<LevelKey, Integer> entry : currentSizes.entrySet()) {
            if (entry.getValue() >= largeOrderThreshold) {
                largeSinceMsByLevel.put(entry.getKey(), nowMs - minLargeOrderLifetimeMs);
            }
        }
        recentTrades.clear();
        PluginLog.info("[WallChange] Alerts armed for " + instrumentAlias);
    }

    public synchronized void shutdown() {
        shutdown = true;
        pendingAdds.clear();
        pendingDecreases.clear();
        largeSinceMsByLevel.clear();
        recentTrades.clear();
        scheduler.shutdownNow();
    }

    private synchronized void evaluatePendingAdd(LevelKey key) {
        if (shutdown || !ready) {
            return;
        }
        PendingAdd pending = pendingAdds.get(key);
        if (pending == null) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        int latestSize = currentSizes.getOrDefault(key, 0);
        if (latestSize < largeOrderThreshold) {
            pendingAdds.remove(key);
            largeSinceMsByLevel.remove(key);
            PluginLog.info("[WallChange] Suppressed flash add for " + instrumentAlias + " "
                    + (key.bid ? "BID" : "ASK") + " " + priceText(key.priceTick)
                    + " lifetime=" + (nowMs - pending.createdAtMs) + "ms");
            return;
        }

        long remainingMs = minLargeOrderLifetimeMs - (nowMs - pending.createdAtMs);
        if (remainingMs > 0) {
            scheduler.schedule(() -> evaluatePendingAdd(key), remainingMs, TimeUnit.MILLISECONDS);
            return;
        }

        pending.latestSize = latestSize;
        promotePendingAdd(key, pending, nowMs);
    }

    private synchronized void evaluatePendingDecrease(LevelKey key) {
        if (shutdown || !ready) {
            return;
        }
        PendingDecrease pending = pendingDecreases.remove(key);
        if (pending == null) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        cleanupRecentTrades(nowMs);
        int latestSize = currentSizes.getOrDefault(key, 0);
        pending.latestSize = latestSize;

        if (!isSignificantDecrease(pending.originalSize, latestSize)) {
            return;
        }

        int dropSize = pending.originalSize - latestSize;
        if (dropSize <= 0) {
            return;
        }

        int tradedSize = sumMatchingTradeSize(key, pending.createdAtMs - TRADE_LOOKBACK_MS, nowMs);
        if (tradedSize >= dropSize * TRADE_EXPLAINED_RATIO) {
            PluginLog.info("[WallChange] Suppressed traded decrease for " + instrumentAlias + " "
                    + (key.bid ? "BID" : "ASK") + " " + priceText(key.priceTick)
                    + " drop=" + dropSize + " traded=" + tradedSize);
            return;
        }

        if (isCoolingDown(key, nowMs)) {
            return;
        }

        OrderWallChangeEvent.Type type = latestSize >= largeOrderThreshold
                ? OrderWallChangeEvent.Type.REPLACED_SMALLER
                : OrderWallChangeEvent.Type.REDUCED;
        OrderWallChangeEvent event = new OrderWallChangeEvent(
                instrumentAlias,
                key.bid,
                key.priceTick,
                key.priceTick * pips,
                pending.originalSize,
                latestSize,
                tradedSize,
                type,
                pending.latestEventTimeNs,
                nowMs);
        markAlerted(key, nowMs);
        alertConsumer.accept(event);
        PluginLog.info("[WallChange] " + event.getLogMessage());
    }

    private void schedulePendingAdd(LevelKey key, int previousSize, int size, long eventTimeNs, long nowMs) {
        PendingAdd pending = new PendingAdd(key, previousSize, size, eventTimeNs, nowMs);
        pendingAdds.put(key, pending);
        scheduler.schedule(() -> evaluatePendingAdd(key),
                minLargeOrderLifetimeMs, TimeUnit.MILLISECONDS);
    }

    private void promotePendingAddIfMature(LevelKey key, int latestSize, long nowMs) {
        PendingAdd pending = pendingAdds.get(key);
        if (pending == null
                || latestSize < largeOrderThreshold
                || nowMs - pending.createdAtMs < minLargeOrderLifetimeMs) {
            return;
        }
        pending.latestSize = latestSize;
        promotePendingAdd(key, pending, nowMs);
    }

    private void promotePendingAdd(LevelKey key, PendingAdd pending, long nowMs) {
        pendingAdds.remove(key);
        largeSinceMsByLevel.put(key, pending.createdAtMs);
        emitAdd(key, pending.previousSize, pending.latestSize, pending.latestEventTimeNs, nowMs);
    }

    private void emitAdd(LevelKey key, int previousSize, int size, long eventTimeNs, long nowMs) {
        if (isCoolingDown(key, nowMs)) {
            return;
        }
        OrderWallChangeEvent event = new OrderWallChangeEvent(
                instrumentAlias,
                key.bid,
                key.priceTick,
                key.priceTick * pips,
                previousSize,
                size,
                0,
                OrderWallChangeEvent.Type.ADDED,
                eventTimeNs,
                nowMs);
        markAlerted(key, nowMs);
        alertConsumer.accept(event);
        PluginLog.info("[WallChange] " + event.getLogMessage());
    }

    private boolean isSignificantDecrease(int previousSize, int currentSize) {
        return currentSize < largeOrderThreshold || currentSize <= previousSize * remainingRatio;
    }

    private boolean isLargeOrderEligible(LevelKey key, long nowMs) {
        Long largeSinceMs = largeSinceMsByLevel.get(key);
        return largeSinceMs != null && nowMs - largeSinceMs >= minLargeOrderLifetimeMs;
    }

    private int sumMatchingTradeSize(LevelKey key, long fromMs, long toMs) {
        int total = 0;
        for (TradeRecord trade : recentTrades) {
            if (trade.observedAtMs < fromMs || trade.observedAtMs > toMs) {
                continue;
            }
            if (trade.priceTick != key.priceTick) {
                continue;
            }
            if (key.bid && !trade.bidAggressor) {
                total += trade.size;
            } else if (!key.bid && trade.bidAggressor) {
                total += trade.size;
            }
        }
        return total;
    }

    private void cleanupRecentTrades(long nowMs) {
        Iterator<TradeRecord> it = recentTrades.iterator();
        while (it.hasNext()) {
            if (nowMs - it.next().observedAtMs > TRADE_RETENTION_MS) {
                it.remove();
            } else {
                break;
            }
        }
    }

    private boolean isCoolingDown(LevelKey key, long nowMs) {
        Long lastAlertMs = lastAlertMsByLevel.get(key);
        return lastAlertMs != null && nowMs - lastAlertMs < ALERT_COOLDOWN_MS;
    }

    private void markAlerted(LevelKey key, long nowMs) {
        lastAlertMsByLevel.put(key, nowMs);
    }

    private String priceText(int priceTick) {
        return String.format("%.4f", priceTick * pips);
    }

    private static class PendingAdd {
        private final LevelKey key;
        private final int previousSize;
        private final long createdAtMs;
        private int latestSize;
        private long latestEventTimeNs;

        private PendingAdd(LevelKey key, int previousSize, int latestSize,
                           long latestEventTimeNs, long createdAtMs) {
            this.key = key;
            this.previousSize = previousSize;
            this.latestSize = latestSize;
            this.latestEventTimeNs = latestEventTimeNs;
            this.createdAtMs = createdAtMs;
        }
    }

    private static class PendingDecrease {
        private final LevelKey key;
        private final int originalSize;
        private final long createdAtMs;
        private int latestSize;
        private long latestEventTimeNs;

        private PendingDecrease(LevelKey key, int originalSize, int latestSize,
                                long latestEventTimeNs, long createdAtMs) {
            this.key = key;
            this.originalSize = originalSize;
            this.latestSize = latestSize;
            this.latestEventTimeNs = latestEventTimeNs;
            this.createdAtMs = createdAtMs;
        }
    }

    private static class TradeRecord {
        private final int priceTick;
        private final int size;
        private final boolean bidAggressor;
        private final long observedAtMs;

        private TradeRecord(int priceTick, int size, boolean bidAggressor, long observedAtMs) {
            this.priceTick = priceTick;
            this.size = size;
            this.bidAggressor = bidAggressor;
            this.observedAtMs = observedAtMs;
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
