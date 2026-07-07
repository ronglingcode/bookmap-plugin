package com.bookmap.plugin.rong.orderwall;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

import com.bookmap.plugin.rong.PluginLog;

import velox.api.layer1.data.TradeInfo;

/**
 * Detects large submitted/pulled order changes while suppressing decreases that
 * are mostly explained by trades at the same price.
 */
public class OrderWallChangeTracker {

    private static final long TRADE_LOOKBACK_MS = 2_000;
    private static final long TRADE_RETENTION_MS = 5_000;
    private static final double TRADE_EXPLAINED_RATIO = 0.70;
    private static final double SAME_PRICE_TRADE_EXPLAINED_RATIO = 0.10;
    private static final long ALERT_COOLDOWN_MS = 2_500;
    private static final long MIN_LARGE_ORDER_LIFETIME_MS = 500;
    private static final double SIZE_INCREASE_RATIO = 2.0;
    private static final Predicate<Boolean> WALL_BREAK_ALERTS_DISABLED = ignored -> false;

    private final String instrumentAlias;
    private final double pips;
    private final IntSupplier largeOrderThresholdSupplier;
    private final double largeOrderPercentile;
    private final double remainingRatio;
    private final long decreaseDecisionDelayMs;
    private final long minLargeOrderLifetimeMs;
    private final Consumer<OrderWallChangeEvent> alertConsumer;
    private final Predicate<Boolean> wallBreakAlertEnabled;
    private final ScheduledExecutorService scheduler;
    private final Map<LevelKey, Integer> currentSizes = new HashMap<>();
    private final Map<LevelKey, PendingAdd> pendingAdds = new HashMap<>();
    private final Map<LevelKey, PendingIncrease> pendingIncreases = new HashMap<>();
    private final Map<LevelKey, PendingDecrease> pendingDecreases = new HashMap<>();
    private final Map<LevelKey, Long> largeSinceMsByLevel = new HashMap<>();
    private final Map<LevelKey, Long> lastAlertMsByLevel = new HashMap<>();
    private final Map<LevelKey, Long> lastWallBreakAlertMsByLevel = new HashMap<>();
    private final TreeMap<Integer, Integer> sizeCounts = new TreeMap<>();
    private final Deque<TradeRecord> recentTrades = new ArrayDeque<>();
    private int totalLevels;

    private boolean ready;
    private boolean shutdown;

    public OrderWallChangeTracker(String instrumentAlias, double pips, int largeOrderThreshold,
                                  double remainingRatio, long decreaseDecisionDelayMs,
                                  Consumer<OrderWallChangeEvent> alertConsumer) {
        this(instrumentAlias, pips, fixedThreshold(largeOrderThreshold), 0, remainingRatio, decreaseDecisionDelayMs,
                MIN_LARGE_ORDER_LIFETIME_MS, alertConsumer, WALL_BREAK_ALERTS_DISABLED);
    }

    public OrderWallChangeTracker(String instrumentAlias, double pips, int largeOrderThreshold,
                                  double largeOrderPercentile, double remainingRatio,
                                  long decreaseDecisionDelayMs,
                                  Consumer<OrderWallChangeEvent> alertConsumer) {
        this(instrumentAlias, pips, fixedThreshold(largeOrderThreshold), largeOrderPercentile,
                remainingRatio, decreaseDecisionDelayMs,
                MIN_LARGE_ORDER_LIFETIME_MS, alertConsumer, WALL_BREAK_ALERTS_DISABLED);
    }

    public OrderWallChangeTracker(String instrumentAlias, double pips, int largeOrderThreshold,
                                  double largeOrderPercentile, double remainingRatio,
                                  long decreaseDecisionDelayMs,
                                  Consumer<OrderWallChangeEvent> alertConsumer,
                                  Predicate<Boolean> wallBreakAlertEnabled) {
        this(instrumentAlias, pips, fixedThreshold(largeOrderThreshold), largeOrderPercentile,
                remainingRatio, decreaseDecisionDelayMs,
                MIN_LARGE_ORDER_LIFETIME_MS, alertConsumer, wallBreakAlertEnabled);
    }

    public OrderWallChangeTracker(String instrumentAlias, double pips,
                                  IntSupplier largeOrderThresholdSupplier,
                                  double largeOrderPercentile, double remainingRatio,
                                  long decreaseDecisionDelayMs,
                                  Consumer<OrderWallChangeEvent> alertConsumer,
                                  Predicate<Boolean> wallBreakAlertEnabled) {
        this(instrumentAlias, pips, largeOrderThresholdSupplier, largeOrderPercentile,
                remainingRatio, decreaseDecisionDelayMs,
                MIN_LARGE_ORDER_LIFETIME_MS, alertConsumer, wallBreakAlertEnabled);
    }

    OrderWallChangeTracker(String instrumentAlias, double pips, int largeOrderThreshold,
                           double remainingRatio, long decreaseDecisionDelayMs,
                           long minLargeOrderLifetimeMs,
                           Consumer<OrderWallChangeEvent> alertConsumer) {
        this(instrumentAlias, pips, fixedThreshold(largeOrderThreshold), 0, remainingRatio,
                decreaseDecisionDelayMs, minLargeOrderLifetimeMs, alertConsumer, WALL_BREAK_ALERTS_DISABLED);
    }

    OrderWallChangeTracker(String instrumentAlias, double pips, int largeOrderThreshold,
                           double remainingRatio, long decreaseDecisionDelayMs,
                           long minLargeOrderLifetimeMs,
                           Consumer<OrderWallChangeEvent> alertConsumer,
                           Predicate<Boolean> wallBreakAlertEnabled) {
        this(instrumentAlias, pips, fixedThreshold(largeOrderThreshold), 0, remainingRatio,
                decreaseDecisionDelayMs, minLargeOrderLifetimeMs, alertConsumer, wallBreakAlertEnabled);
    }

    OrderWallChangeTracker(String instrumentAlias, double pips, int largeOrderThreshold,
                           double largeOrderPercentile, double remainingRatio,
                           long decreaseDecisionDelayMs, long minLargeOrderLifetimeMs,
                           Consumer<OrderWallChangeEvent> alertConsumer) {
        this(instrumentAlias, pips, fixedThreshold(largeOrderThreshold), largeOrderPercentile, remainingRatio,
                decreaseDecisionDelayMs, minLargeOrderLifetimeMs, alertConsumer, WALL_BREAK_ALERTS_DISABLED);
    }

    OrderWallChangeTracker(String instrumentAlias, double pips,
                           IntSupplier largeOrderThresholdSupplier,
                           double largeOrderPercentile, double remainingRatio,
                           long decreaseDecisionDelayMs, long minLargeOrderLifetimeMs,
                           Consumer<OrderWallChangeEvent> alertConsumer,
                           Predicate<Boolean> wallBreakAlertEnabled) {
        this.instrumentAlias = instrumentAlias;
        this.pips = pips;
        this.largeOrderThresholdSupplier = largeOrderThresholdSupplier == null
                ? fixedThreshold(0)
                : largeOrderThresholdSupplier;
        this.largeOrderPercentile = largeOrderPercentile;
        this.remainingRatio = remainingRatio;
        this.decreaseDecisionDelayMs = decreaseDecisionDelayMs;
        this.minLargeOrderLifetimeMs = minLargeOrderLifetimeMs;
        this.alertConsumer = alertConsumer;
        this.wallBreakAlertEnabled = wallBreakAlertEnabled == null
                ? WALL_BREAK_ALERTS_DISABLED
                : wallBreakAlertEnabled;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wall-change-alert-" + instrumentAlias);
            t.setDaemon(true);
            return t;
        });
    }

    private static IntSupplier fixedThreshold(int largeOrderThreshold) {
        int normalizedThreshold = Math.max(0, largeOrderThreshold);
        return () -> normalizedThreshold;
    }

    public synchronized void onDepth(boolean isBid, int priceTick, int size, long eventTimeNs) {
        if (shutdown) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        cleanupRecentTrades(nowMs);

        LevelKey key = new LevelKey(isBid, priceTick);
        int previousSize = currentSizes.getOrDefault(key, 0);
        int previousThreshold = getEffectiveLargeOrderThreshold();
        if (ready) {
            promotePendingAddIfMature(key, previousSize, previousThreshold, nowMs);
        }
        PendingAdd existingPendingAdd = pendingAdds.get(key);
        PendingIncrease existingPendingIncrease = pendingIncreases.get(key);
        boolean wasLargeOrderEligible = isLargeOrderEligible(key, previousThreshold, nowMs);

        updateCurrentSize(key, size);
        int currentThreshold = getEffectiveLargeOrderThreshold();

        PendingDecrease existingPending = pendingDecreases.get(key);
        if (existingPending != null) {
            existingPending.latestSize = size;
            existingPending.latestEventTimeNs = eventTimeNs;
        }

        if (!ready || previousSize == size) {
            return;
        }

        if (existingPendingAdd != null) {
            if (size >= currentThreshold) {
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

        if (existingPendingIncrease != null) {
            if (isSignificantIncrease(existingPendingIncrease.previousSize, size, currentThreshold)) {
                existingPendingIncrease.latestSize = size;
                existingPendingIncrease.latestEventTimeNs = eventTimeNs;
                return;
            }
            pendingIncreases.remove(key);
            PluginLog.info("[WallChange] Suppressed flash increase for " + instrumentAlias + " "
                    + (key.bid ? "BID" : "ASK") + " " + priceText(key.priceTick)
                    + " lifetime=" + (nowMs - existingPendingIncrease.createdAtMs) + "ms");
            previousSize = existingPendingIncrease.previousSize;
        }

        if (previousSize < previousThreshold && size >= currentThreshold) {
            schedulePendingAdd(key, previousSize, size, eventTimeNs, nowMs);
            return;
        }

        if (previousSize >= previousThreshold && isSignificantIncrease(previousSize, size, currentThreshold)) {
            schedulePendingIncrease(key, previousSize, size, eventTimeNs, nowMs);
            return;
        }

        if (size < currentThreshold) {
            largeSinceMsByLevel.remove(key);
            pendingIncreases.remove(key);
        }

        if (previousSize >= previousThreshold
                && size < previousSize
                && isSignificantDecrease(previousSize, size, currentThreshold)) {
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
        recordTrade(priceTick, size, tradeInfo.isBidAggressor);
    }

    synchronized void onTrade(int priceTick, int size, boolean bidAggressor) {
        if (shutdown || size <= 0) {
            return;
        }
        recordTrade(priceTick, size, bidAggressor);
    }

    private void recordTrade(int priceTick, int size, boolean bidAggressor) {
        long nowMs = System.currentTimeMillis();
        recentTrades.addLast(new TradeRecord(priceTick, size, bidAggressor, nowMs));
        cleanupRecentTrades(nowMs);
    }

    public synchronized void markReady() {
        if (ready) {
            return;
        }
        ready = true;
        long nowMs = System.currentTimeMillis();
        pendingAdds.clear();
        pendingIncreases.clear();
        pendingDecreases.clear();
        largeSinceMsByLevel.clear();
        int currentThreshold = getEffectiveLargeOrderThreshold();
        for (Map.Entry<LevelKey, Integer> entry : currentSizes.entrySet()) {
            if (entry.getValue() >= currentThreshold) {
                largeSinceMsByLevel.put(entry.getKey(), nowMs - minLargeOrderLifetimeMs);
            }
        }
        recentTrades.clear();
        PluginLog.info("[WallChange] Alerts armed for " + instrumentAlias);
    }

    public synchronized void shutdown() {
        shutdown = true;
        pendingAdds.clear();
        pendingIncreases.clear();
        pendingDecreases.clear();
        largeSinceMsByLevel.clear();
        lastWallBreakAlertMsByLevel.clear();
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
        int currentThreshold = getEffectiveLargeOrderThreshold();
        if (latestSize < currentThreshold) {
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
        int currentThreshold = getEffectiveLargeOrderThreshold();

        if (!isSignificantDecrease(pending.originalSize, latestSize, currentThreshold)) {
            return;
        }

        int dropSize = pending.originalSize - latestSize;
        if (dropSize <= 0) {
            return;
        }

        long tradeWindowStartMs = pending.createdAtMs - TRADE_LOOKBACK_MS;
        int tradedSize = sumMatchingTradeSize(key, tradeWindowStartMs, nowMs);
        if (tradedSize >= dropSize * TRADE_EXPLAINED_RATIO) {
            if (latestSize < currentThreshold && emitWallBreak(key, pending.originalSize,
                    latestSize, tradedSize, pending.latestEventTimeNs, nowMs)) {
                return;
            }
            PluginLog.info("[WallChange] Suppressed traded decrease for " + instrumentAlias + " "
                    + (key.bid ? "BID" : "ASK") + " " + priceText(key.priceTick)
                    + " drop=" + dropSize + " traded=" + tradedSize);
            return;
        }

        int samePriceTradeSize = sumSamePriceTradeSize(key.priceTick, tradeWindowStartMs, nowMs);
        if (latestSize < currentThreshold
                && samePriceTradeSize >= dropSize * SAME_PRICE_TRADE_EXPLAINED_RATIO) {
            PluginLog.info("[WallChange] Suppressed price-touched decrease for " + instrumentAlias + " "
                    + (key.bid ? "BID" : "ASK") + " " + priceText(key.priceTick)
                    + " drop=" + dropSize + " samePriceTraded=" + samePriceTradeSize);
            return;
        }

        if (isCoolingDown(key, nowMs)) {
            return;
        }

        OrderWallChangeEvent.Type type = latestSize >= currentThreshold
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

    private synchronized void evaluatePendingIncrease(LevelKey key) {
        if (shutdown || !ready) {
            return;
        }
        PendingIncrease pending = pendingIncreases.get(key);
        if (pending == null) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        int latestSize = currentSizes.getOrDefault(key, 0);
        int currentThreshold = getEffectiveLargeOrderThreshold();
        if (!isSignificantIncrease(pending.previousSize, latestSize, currentThreshold)) {
            pendingIncreases.remove(key);
            PluginLog.info("[WallChange] Suppressed flash increase for " + instrumentAlias + " "
                    + (key.bid ? "BID" : "ASK") + " " + priceText(key.priceTick)
                    + " lifetime=" + (nowMs - pending.createdAtMs) + "ms");
            return;
        }

        long remainingMs = minLargeOrderLifetimeMs - (nowMs - pending.createdAtMs);
        if (remainingMs > 0) {
            scheduler.schedule(() -> evaluatePendingIncrease(key), remainingMs, TimeUnit.MILLISECONDS);
            return;
        }

        pending.latestSize = latestSize;
        pendingIncreases.remove(key);
        emitIncrease(key, pending.previousSize, pending.latestSize, pending.latestEventTimeNs, nowMs);
    }

    private void schedulePendingAdd(LevelKey key, int previousSize, int size, long eventTimeNs, long nowMs) {
        PendingAdd pending = new PendingAdd(key, previousSize, size, eventTimeNs, nowMs);
        pendingAdds.put(key, pending);
        scheduler.schedule(() -> evaluatePendingAdd(key),
                minLargeOrderLifetimeMs, TimeUnit.MILLISECONDS);
    }

    private void schedulePendingIncrease(LevelKey key, int previousSize, int size, long eventTimeNs, long nowMs) {
        PendingIncrease pending = new PendingIncrease(key, previousSize, size, eventTimeNs, nowMs);
        pendingIncreases.put(key, pending);
        scheduler.schedule(() -> evaluatePendingIncrease(key),
                minLargeOrderLifetimeMs, TimeUnit.MILLISECONDS);
    }

    private void promotePendingAddIfMature(LevelKey key, int latestSize, int currentThreshold, long nowMs) {
        PendingAdd pending = pendingAdds.get(key);
        if (pending == null
                || latestSize < currentThreshold
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

    private void emitIncrease(LevelKey key, int previousSize, int size, long eventTimeNs, long nowMs) {
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
                OrderWallChangeEvent.Type.INCREASED,
                eventTimeNs,
                nowMs);
        markAlerted(key, nowMs);
        alertConsumer.accept(event);
        PluginLog.info("[WallChange] " + event.getLogMessage());
    }

    private boolean emitWallBreak(LevelKey key, int previousSize, int size, int tradedSize,
                                  long eventTimeNs, long nowMs) {
        if (!isWallBreakAlertEnabled(key)) {
            return false;
        }
        if (isWallBreakCoolingDown(key, nowMs)) {
            return true;
        }
        OrderWallChangeEvent event = new OrderWallChangeEvent(
                instrumentAlias,
                key.bid,
                key.priceTick,
                key.priceTick * pips,
                previousSize,
                size,
                tradedSize,
                key.bid
                        ? OrderWallChangeEvent.Type.BID_BREAKDOWN
                        : OrderWallChangeEvent.Type.OFFER_BREAKOUT,
                eventTimeNs,
                nowMs);
        markWallBreakAlerted(key, nowMs);
        alertConsumer.accept(event);
        PluginLog.info("[WallBreak] " + event.getLogMessage()
                + " traded=" + OrderWallChangeEvent.formatSize(tradedSize));
        return true;
    }

    private boolean isSignificantDecrease(int previousSize, int currentSize, int currentThreshold) {
        return currentSize < currentThreshold || currentSize <= previousSize * remainingRatio;
    }

    private boolean isSignificantIncrease(int previousSize, int currentSize, int currentThreshold) {
        return previousSize > 0
                && currentSize >= currentThreshold
                && currentSize >= previousSize * SIZE_INCREASE_RATIO;
    }

    private boolean isLargeOrderEligible(LevelKey key, int currentThreshold, long nowMs) {
        Long largeSinceMs = largeSinceMsByLevel.get(key);
        return largeSinceMs != null
                && currentSizes.getOrDefault(key, 0) >= currentThreshold
                && nowMs - largeSinceMs >= minLargeOrderLifetimeMs;
    }

    private int getEffectiveLargeOrderThreshold() {
        int largeOrderThreshold = getAbsoluteLargeOrderThreshold();
        if (largeOrderPercentile <= 0 || totalLevels == 0) {
            return largeOrderThreshold;
        }
        return Math.max(largeOrderThreshold, getPercentileThreshold(largeOrderPercentile));
    }

    private int getAbsoluteLargeOrderThreshold() {
        try {
            return Math.max(0, largeOrderThresholdSupplier.getAsInt());
        } catch (RuntimeException e) {
            PluginLog.error("[WallChange] Failed to read wall threshold floor for "
                    + instrumentAlias + ": " + e.getMessage());
            return 0;
        }
    }

    private int getPercentileThreshold(double percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * totalLevels) - 1;
        index = Math.max(0, Math.min(index, totalLevels - 1));

        int cumulative = 0;
        for (Map.Entry<Integer, Integer> entry : sizeCounts.entrySet()) {
            cumulative += entry.getValue();
            if (cumulative > index) {
                return entry.getKey();
            }
        }
        return sizeCounts.isEmpty() ? 0 : sizeCounts.lastKey();
    }

    private void updateCurrentSize(LevelKey key, int size) {
        Integer previousSize = currentSizes.get(key);
        if (previousSize != null) {
            decrementSizeCount(previousSize);
            totalLevels--;
        }
        if (size == 0) {
            currentSizes.remove(key);
            return;
        }
        currentSizes.put(key, size);
        incrementSizeCount(size);
        totalLevels++;
    }

    private void incrementSizeCount(int size) {
        sizeCounts.merge(size, 1, Integer::sum);
    }

    private void decrementSizeCount(int size) {
        sizeCounts.computeIfPresent(size, (ignored, count) -> count > 1 ? count - 1 : null);
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

    private int sumSamePriceTradeSize(int priceTick, long fromMs, long toMs) {
        int total = 0;
        for (TradeRecord trade : recentTrades) {
            if (trade.observedAtMs < fromMs || trade.observedAtMs > toMs) {
                continue;
            }
            if (trade.priceTick == priceTick) {
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

    private boolean isWallBreakAlertEnabled(LevelKey key) {
        try {
            return wallBreakAlertEnabled.test(key.bid);
        } catch (RuntimeException e) {
            PluginLog.error("[WallBreak] Failed to check alert enablement for "
                    + instrumentAlias + ": " + e.getMessage());
            return false;
        }
    }

    private boolean isWallBreakCoolingDown(LevelKey key, long nowMs) {
        Long lastAlertMs = lastWallBreakAlertMsByLevel.get(key);
        return lastAlertMs != null && nowMs - lastAlertMs < ALERT_COOLDOWN_MS;
    }

    private void markWallBreakAlerted(LevelKey key, long nowMs) {
        lastWallBreakAlertMsByLevel.put(key, nowMs);
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

    private static class PendingIncrease {
        private final LevelKey key;
        private final int previousSize;
        private final long createdAtMs;
        private int latestSize;
        private long latestEventTimeNs;

        private PendingIncrease(LevelKey key, int previousSize, int latestSize,
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
