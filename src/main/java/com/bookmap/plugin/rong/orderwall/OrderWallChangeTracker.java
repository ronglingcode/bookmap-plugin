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
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

import com.bookmap.plugin.rong.BookmapPriceNormalizer;

import velox.api.layer1.data.TradeInfo;

/**
 * Tracks the aggregate depth size at every price and emits material changes after
 * they remain in the book for a short stability window.
 */
public class OrderWallChangeTracker {

    private static final long TRADE_LOOKBACK_MS = 2_000;
    private static final long TRADE_RETENTION_MS = 5_000;
    private static final double TRADE_EXPLAINED_RATIO = 0.70;
    private static final double SAME_PRICE_TRADE_EXPLAINED_RATIO = 0.10;
    private static final long MOVE_PAIR_WINDOW_MS = 500;
    private static final double MOVE_SIZE_TOLERANCE_RATIO = 0.10;
    private static final long DEFAULT_CHANGE_SURVIVAL_MS = 500;
    private static final int DEFAULT_LARGEST_ORDER_RANK = 5;
    private static final int MAX_ORDER_CHANGE_THRESHOLD = 10_000;
    private static final Predicate<Boolean> WALL_BREAK_ALERTS_DISABLED = ignored -> false;
    private static final DoubleSupplier UNKNOWN_DAY_LEVEL = () -> Double.NaN;

    private final String instrumentAlias;
    private final double pips;
    private final IntSupplier largeOrderThresholdSupplier;
    private final int largestOrderRank;
    private final long changeSurvivalMs;
    private final Consumer<OrderWallChangeEvent> alertConsumer;
    private final Predicate<Boolean> wallBreakAlertEnabled;
    private final DoubleSupplier dayHighSupplier;
    private final DoubleSupplier dayLowSupplier;
    private final ScheduledExecutorService scheduler;
    private final Map<LevelKey, Integer> currentSizes = new HashMap<>();
    private final Map<LevelKey, PendingChange> pendingChanges = new HashMap<>();
    private final TreeMap<Integer, Integer> sizeCounts = new TreeMap<>();
    private final Deque<TradeRecord> recentTrades = new ArrayDeque<>();
    private int totalLevels;
    private boolean ready;
    private boolean shutdown;

    public OrderWallChangeTracker(String instrumentAlias, double pips, int largeOrderThreshold,
                                  double remainingRatio, long decreaseDecisionDelayMs,
                                  Consumer<OrderWallChangeEvent> alertConsumer) {
        this(instrumentAlias, pips, fixedThreshold(largeOrderThreshold), 0,
                decreaseDecisionDelayMs, DEFAULT_CHANGE_SURVIVAL_MS, alertConsumer,
                WALL_BREAK_ALERTS_DISABLED, UNKNOWN_DAY_LEVEL, UNKNOWN_DAY_LEVEL);
    }

    public OrderWallChangeTracker(String instrumentAlias, double pips, int largeOrderThreshold,
                                  double largeOrderPercentile, double remainingRatio,
                                  long decreaseDecisionDelayMs,
                                  Consumer<OrderWallChangeEvent> alertConsumer) {
        this(instrumentAlias, pips, fixedThreshold(largeOrderThreshold),
                rankForLegacyPercentile(largeOrderPercentile),
                decreaseDecisionDelayMs, DEFAULT_CHANGE_SURVIVAL_MS, alertConsumer,
                WALL_BREAK_ALERTS_DISABLED, UNKNOWN_DAY_LEVEL, UNKNOWN_DAY_LEVEL);
    }

    public OrderWallChangeTracker(String instrumentAlias, double pips, int largeOrderThreshold,
                                  int largestOrderRank, double remainingRatio,
                                  long decreaseDecisionDelayMs,
                                  Consumer<OrderWallChangeEvent> alertConsumer) {
        this(instrumentAlias, pips, fixedThreshold(largeOrderThreshold), largestOrderRank,
                decreaseDecisionDelayMs, DEFAULT_CHANGE_SURVIVAL_MS, alertConsumer,
                WALL_BREAK_ALERTS_DISABLED, UNKNOWN_DAY_LEVEL, UNKNOWN_DAY_LEVEL);
    }

    public OrderWallChangeTracker(String instrumentAlias, double pips, int largeOrderThreshold,
                                  double largeOrderPercentile, double remainingRatio,
                                  long decreaseDecisionDelayMs,
                                  Consumer<OrderWallChangeEvent> alertConsumer,
                                  Predicate<Boolean> wallBreakAlertEnabled) {
        this(instrumentAlias, pips, fixedThreshold(largeOrderThreshold),
                rankForLegacyPercentile(largeOrderPercentile),
                decreaseDecisionDelayMs, DEFAULT_CHANGE_SURVIVAL_MS, alertConsumer,
                wallBreakAlertEnabled, UNKNOWN_DAY_LEVEL, UNKNOWN_DAY_LEVEL);
    }

    public OrderWallChangeTracker(String instrumentAlias, double pips,
                                  IntSupplier largeOrderThresholdSupplier,
                                  double largeOrderPercentile, double remainingRatio,
                                  long decreaseDecisionDelayMs,
                                  Consumer<OrderWallChangeEvent> alertConsumer,
                                  Predicate<Boolean> wallBreakAlertEnabled) {
        this(instrumentAlias, pips, largeOrderThresholdSupplier,
                rankForLegacyPercentile(largeOrderPercentile),
                decreaseDecisionDelayMs, DEFAULT_CHANGE_SURVIVAL_MS, alertConsumer,
                wallBreakAlertEnabled, UNKNOWN_DAY_LEVEL, UNKNOWN_DAY_LEVEL);
    }

    public OrderWallChangeTracker(String instrumentAlias, double pips,
                                  IntSupplier largeOrderThresholdSupplier,
                                  int largestOrderRank, double remainingRatio,
                                  long decreaseDecisionDelayMs,
                                  Consumer<OrderWallChangeEvent> alertConsumer,
                                  Predicate<Boolean> wallBreakAlertEnabled,
                                  DoubleSupplier dayHighSupplier,
                                  DoubleSupplier dayLowSupplier) {
        this(instrumentAlias, pips, largeOrderThresholdSupplier, largestOrderRank,
                decreaseDecisionDelayMs, DEFAULT_CHANGE_SURVIVAL_MS, alertConsumer,
                wallBreakAlertEnabled, dayHighSupplier, dayLowSupplier);
    }

    public OrderWallChangeTracker(String instrumentAlias, double pips,
                                  IntSupplier largeOrderThresholdSupplier,
                                  double largeOrderPercentile, double remainingRatio,
                                  long decreaseDecisionDelayMs,
                                  Consumer<OrderWallChangeEvent> alertConsumer,
                                  Predicate<Boolean> wallBreakAlertEnabled,
                                  DoubleSupplier dayHighSupplier,
                                  DoubleSupplier dayLowSupplier) {
        this(instrumentAlias, pips, largeOrderThresholdSupplier,
                rankForLegacyPercentile(largeOrderPercentile),
                decreaseDecisionDelayMs, DEFAULT_CHANGE_SURVIVAL_MS, alertConsumer,
                wallBreakAlertEnabled, dayHighSupplier, dayLowSupplier);
    }

    OrderWallChangeTracker(String instrumentAlias, double pips, int largeOrderThreshold,
                           double remainingRatio, long decreaseDecisionDelayMs,
                           long minLargeOrderLifetimeMs,
                           Consumer<OrderWallChangeEvent> alertConsumer) {
        this(instrumentAlias, pips, fixedThreshold(largeOrderThreshold), 0,
                decreaseDecisionDelayMs, minLargeOrderLifetimeMs, alertConsumer,
                WALL_BREAK_ALERTS_DISABLED, UNKNOWN_DAY_LEVEL, UNKNOWN_DAY_LEVEL);
    }

    OrderWallChangeTracker(String instrumentAlias, double pips, int largeOrderThreshold,
                           double remainingRatio, long decreaseDecisionDelayMs,
                           long minLargeOrderLifetimeMs,
                           Consumer<OrderWallChangeEvent> alertConsumer,
                           Predicate<Boolean> wallBreakAlertEnabled) {
        this(instrumentAlias, pips, fixedThreshold(largeOrderThreshold), 0,
                decreaseDecisionDelayMs, minLargeOrderLifetimeMs, alertConsumer,
                wallBreakAlertEnabled, UNKNOWN_DAY_LEVEL, UNKNOWN_DAY_LEVEL);
    }

    OrderWallChangeTracker(String instrumentAlias, double pips, int largeOrderThreshold,
                           double largeOrderPercentile, double remainingRatio,
                           long decreaseDecisionDelayMs, long minLargeOrderLifetimeMs,
                           Consumer<OrderWallChangeEvent> alertConsumer) {
        this(instrumentAlias, pips, fixedThreshold(largeOrderThreshold),
                rankForLegacyPercentile(largeOrderPercentile),
                decreaseDecisionDelayMs, minLargeOrderLifetimeMs, alertConsumer,
                WALL_BREAK_ALERTS_DISABLED, UNKNOWN_DAY_LEVEL, UNKNOWN_DAY_LEVEL);
    }

    OrderWallChangeTracker(String instrumentAlias, double pips,
                           IntSupplier largeOrderThresholdSupplier,
                           double largeOrderPercentile, double remainingRatio,
                           long decreaseDecisionDelayMs, long minLargeOrderLifetimeMs,
                           Consumer<OrderWallChangeEvent> alertConsumer,
                           Predicate<Boolean> wallBreakAlertEnabled) {
        this(instrumentAlias, pips, largeOrderThresholdSupplier,
                rankForLegacyPercentile(largeOrderPercentile),
                decreaseDecisionDelayMs, minLargeOrderLifetimeMs, alertConsumer,
                wallBreakAlertEnabled, UNKNOWN_DAY_LEVEL, UNKNOWN_DAY_LEVEL);
    }

    OrderWallChangeTracker(String instrumentAlias, double pips,
                           IntSupplier largeOrderThresholdSupplier,
                           int largestOrderRank, long changeSurvivalMs,
                           Consumer<OrderWallChangeEvent> alertConsumer,
                           Predicate<Boolean> wallBreakAlertEnabled,
                           DoubleSupplier dayHighSupplier,
                           DoubleSupplier dayLowSupplier) {
        this(instrumentAlias, pips, largeOrderThresholdSupplier, largestOrderRank,
                changeSurvivalMs, changeSurvivalMs, alertConsumer, wallBreakAlertEnabled,
                dayHighSupplier, dayLowSupplier);
    }

    private OrderWallChangeTracker(String instrumentAlias, double pips,
                                   IntSupplier largeOrderThresholdSupplier,
                                   int largestOrderRank,
                                   long decreaseDecisionDelayMs,
                                   long minLargeOrderLifetimeMs,
                                   Consumer<OrderWallChangeEvent> alertConsumer,
                                   Predicate<Boolean> wallBreakAlertEnabled,
                                   DoubleSupplier dayHighSupplier,
                                   DoubleSupplier dayLowSupplier) {
        this.instrumentAlias = instrumentAlias;
        this.pips = pips;
        this.largeOrderThresholdSupplier = largeOrderThresholdSupplier == null
                ? fixedThreshold(0)
                : largeOrderThresholdSupplier;
        this.largestOrderRank = Math.max(0, largestOrderRank);
        this.changeSurvivalMs = Math.max(0,
                Math.max(decreaseDecisionDelayMs, minLargeOrderLifetimeMs));
        this.alertConsumer = alertConsumer;
        this.wallBreakAlertEnabled = wallBreakAlertEnabled == null
                ? WALL_BREAK_ALERTS_DISABLED
                : wallBreakAlertEnabled;
        this.dayHighSupplier = dayHighSupplier == null ? UNKNOWN_DAY_LEVEL : dayHighSupplier;
        this.dayLowSupplier = dayLowSupplier == null ? UNKNOWN_DAY_LEVEL : dayLowSupplier;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "wall-change-alert-" + instrumentAlias);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static IntSupplier fixedThreshold(int largeOrderThreshold) {
        int normalizedThreshold = Math.max(0, largeOrderThreshold);
        return () -> normalizedThreshold;
    }

    private static int rankForLegacyPercentile(double percentile) {
        return percentile > 0 ? DEFAULT_LARGEST_ORDER_RANK : 0;
    }

    public synchronized void onDepth(boolean isBid, int priceTick, int size, long eventTimeNs) {
        if (shutdown) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        cleanupRecentTrades(nowMs);

        LevelKey key = new LevelKey(isBid, priceTick);
        int previousSize = currentSizes.getOrDefault(key, 0);
        updateCurrentSize(key, size);

        if (!ready || previousSize == size) {
            return;
        }

        PendingChange pending = pendingChanges.get(key);
        if (pending != null) {
            pending.latestEventTimeNs = eventTimeNs;
            return;
        }

        int threshold = getEffectiveLargeOrderThreshold();
        if (!isMaterialChange(previousSize, size, threshold)) {
            return;
        }

        PendingChange newPending = new PendingChange(previousSize, eventTimeNs, nowMs);
        pendingChanges.put(key, newPending);
        scheduler.schedule(() -> evaluatePendingChange(key),
                changeSurvivalMs, TimeUnit.MILLISECONDS);
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
        pendingChanges.clear();
        recentTrades.clear();
    }

    public synchronized void shutdown() {
        shutdown = true;
        pendingChanges.clear();
        recentTrades.clear();
        scheduler.shutdownNow();
    }

    private synchronized void evaluatePendingChange(LevelKey key) {
        if (shutdown || !ready) {
            return;
        }
        PendingChange pending = pendingChanges.get(key);
        if (pending == null) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        cleanupRecentTrades(nowMs);
        int threshold = getEffectiveLargeOrderThreshold();
        EvaluatedChange change = evaluateMaterialChange(key, pending, threshold, nowMs);
        if (change == null) {
            pendingChanges.remove(key);
            return;
        }

        MoveMatch moveMatch = findMoveMatch(change, threshold, nowMs);
        if (moveMatch != null) {
            long remainingMs = changeSurvivalMs - (nowMs - moveMatch.pending.createdAtMs);
            if (remainingMs > 0) {
                scheduler.schedule(() -> evaluatePendingChange(key), remainingMs, TimeUnit.MILLISECONDS);
                return;
            }
            pendingChanges.remove(key);
            pendingChanges.remove(moveMatch.change.key);
            emitMovedOrder(change, moveMatch.change, threshold, nowMs);
            return;
        }

        pendingChanges.remove(key);
        emitSingleChange(change, threshold, nowMs);
    }

    private EvaluatedChange evaluateMaterialChange(LevelKey key, PendingChange pending,
                                                    int threshold, long nowMs) {
        int currentSize = currentSizes.getOrDefault(key, 0);
        boolean crossed = crossedThreshold(pending.previousSize, currentSize, threshold);
        boolean deltaExceeds = deltaExceedsThreshold(pending.previousSize, currentSize, threshold);
        if (!crossed && !deltaExceeds) {
            return null;
        }

        int dropSize = Math.max(0, pending.previousSize - currentSize);
        long tradeWindowStartMs = pending.createdAtMs - TRADE_LOOKBACK_MS;
        int matchingTradeSize = dropSize == 0
                ? 0
                : sumMatchingTradeSize(key, tradeWindowStartMs, nowMs);
        int samePriceTradeSize = dropSize == 0
                ? 0
                : sumSamePriceTradeSize(key.priceTick, tradeWindowStartMs, nowMs);
        boolean tradeConsumption = dropSize > 0
                && (matchingTradeSize >= dropSize * TRADE_EXPLAINED_RATIO
                    || (currentSize < threshold
                        && samePriceTradeSize >= dropSize * SAME_PRICE_TRADE_EXPLAINED_RATIO));
        return new EvaluatedChange(
                key,
                pending,
                currentSize,
                currentSize - pending.previousSize,
                crossed,
                deltaExceeds,
                tradeConsumption,
                Math.max(matchingTradeSize, samePriceTradeSize));
    }

    private MoveMatch findMoveMatch(EvaluatedChange change, int threshold, long nowMs) {
        if (change.tradeConsumption) {
            return null;
        }
        MoveMatch bestMatch = null;
        long bestTimeDistanceMs = Long.MAX_VALUE;
        int bestSizeDistance = Integer.MAX_VALUE;
        for (Map.Entry<LevelKey, PendingChange> entry : pendingChanges.entrySet()) {
            LevelKey otherKey = entry.getKey();
            PendingChange otherPending = entry.getValue();
            if (otherKey.equals(change.key)
                    || otherKey.bid != change.key.bid
                    || Math.abs(otherPending.createdAtMs - change.pending.createdAtMs)
                            > MOVE_PAIR_WINDOW_MS) {
                continue;
            }
            EvaluatedChange other = evaluateMaterialChange(otherKey, otherPending, threshold, nowMs);
            if (other == null || other.tradeConsumption || !hasOppositeDirection(change, other)
                    || !hasSimilarMovedSize(change, other)) {
                continue;
            }
            long timeDistanceMs = Math.abs(
                    other.pending.createdAtMs - change.pending.createdAtMs);
            int sizeDistance = Math.abs(Math.abs(other.delta) - Math.abs(change.delta));
            if (timeDistanceMs < bestTimeDistanceMs
                    || (timeDistanceMs == bestTimeDistanceMs && sizeDistance < bestSizeDistance)) {
                bestMatch = new MoveMatch(otherPending, other);
                bestTimeDistanceMs = timeDistanceMs;
                bestSizeDistance = sizeDistance;
            }
        }
        return bestMatch;
    }

    private static boolean hasOppositeDirection(EvaluatedChange first, EvaluatedChange second) {
        return (first.delta < 0 && second.delta > 0)
                || (first.delta > 0 && second.delta < 0);
    }

    private static boolean hasSimilarMovedSize(EvaluatedChange first, EvaluatedChange second) {
        int firstSize = Math.abs(first.delta);
        int secondSize = Math.abs(second.delta);
        int tolerance = Math.max(1,
                (int) Math.round(Math.max(firstSize, secondSize) * MOVE_SIZE_TOLERANCE_RATIO));
        return Math.abs(firstSize - secondSize) <= tolerance;
    }

    private void emitSingleChange(EvaluatedChange change, int threshold, long nowMs) {
        double realPrice = BookmapPriceNormalizer.toWirePrice(change.key.priceTick, pips);
        double dayHigh = getDayLevel(dayHighSupplier);
        double dayLow = getDayLevel(dayLowSupplier);
        boolean withinDayRange = isWithinDayRange(change.key.bid, realPrice, dayHigh, dayLow);

        OrderWallChangeEvent.Type type = typeFor(
                change.key, change.pending.previousSize, change.currentSize,
                threshold, change.tradeConsumption);
        OrderWallChangeEvent event = new OrderWallChangeEvent(
                instrumentAlias,
                change.key.bid,
                change.key.priceTick,
                realPrice,
                change.pending.previousSize,
                change.currentSize,
                change.tradedSize,
                type,
                change.pending.latestEventTimeNs,
                nowMs,
                threshold,
                change.crossedThreshold,
                change.deltaExceedsThreshold,
                change.tradeConsumption,
                withinDayRange,
                dayHigh,
                dayLow);
        alertConsumer.accept(event);
    }

    private void emitMovedOrder(EvaluatedChange first, EvaluatedChange second,
                                int threshold, long nowMs) {
        EvaluatedChange source = first.delta < 0 ? first : second;
        EvaluatedChange destination = first.delta > 0 ? first : second;
        double sourceRealPrice = BookmapPriceNormalizer.toWirePrice(source.key.priceTick, pips);
        double destinationRealPrice = BookmapPriceNormalizer.toWirePrice(
                destination.key.priceTick, pips);
        double dayHigh = getDayLevel(dayHighSupplier);
        double dayLow = getDayLevel(dayLowSupplier);
        boolean withinDayRange = isWithinDayRange(
                destination.key.bid, destinationRealPrice, dayHigh, dayLow);
        boolean movedUp = destination.key.priceTick > source.key.priceTick;
        OrderWallChangeEvent.Type type;
        if (destination.key.bid) {
            type = movedUp
                    ? OrderWallChangeEvent.Type.BID_MOVED_UP
                    : OrderWallChangeEvent.Type.BID_MOVED_DOWN;
        } else {
            type = movedUp
                    ? OrderWallChangeEvent.Type.OFFER_MOVED_UP
                    : OrderWallChangeEvent.Type.OFFER_MOVED_DOWN;
        }
        OrderWallChangeEvent event = new OrderWallChangeEvent(
                instrumentAlias,
                destination.key.bid,
                destination.key.priceTick,
                destinationRealPrice,
                source.pending.previousSize,
                destination.currentSize,
                source.tradedSize,
                type,
                Math.max(source.pending.latestEventTimeNs, destination.pending.latestEventTimeNs),
                nowMs,
                threshold,
                source.crossedThreshold || destination.crossedThreshold,
                source.deltaExceedsThreshold || destination.deltaExceedsThreshold,
                false,
                withinDayRange,
                dayHigh,
                dayLow,
                source.key.priceTick,
                sourceRealPrice,
                Math.min(Math.abs(source.delta), Math.abs(destination.delta)));
        alertConsumer.accept(event);
    }

    private static boolean isWithinDayRange(boolean bid, double realPrice,
                                            double dayHigh, double dayLow) {
        return bid
                ? Double.isFinite(dayLow) && realPrice > dayLow
                : Double.isFinite(dayHigh) && realPrice < dayHigh;
    }

    private OrderWallChangeEvent.Type typeFor(LevelKey key, int previousSize, int currentSize,
                                               int threshold, boolean tradeConsumption) {
        if (currentSize > previousSize) {
            return previousSize < threshold && currentSize >= threshold
                    ? OrderWallChangeEvent.Type.ADDED
                    : OrderWallChangeEvent.Type.INCREASED;
        }
        if (tradeConsumption && currentSize < threshold && isWallBreakAlertEnabled(key)) {
            return key.bid
                    ? OrderWallChangeEvent.Type.BID_BREAKDOWN
                    : OrderWallChangeEvent.Type.OFFER_BREAKOUT;
        }
        return currentSize >= threshold
                ? OrderWallChangeEvent.Type.REPLACED_SMALLER
                : OrderWallChangeEvent.Type.REDUCED;
    }

    private static boolean isMaterialChange(int previousSize, int currentSize, int threshold) {
        return crossedThreshold(previousSize, currentSize, threshold)
                || deltaExceedsThreshold(previousSize, currentSize, threshold);
    }

    private static boolean crossedThreshold(int previousSize, int currentSize, int threshold) {
        return (previousSize < threshold && currentSize >= threshold)
                || (previousSize >= threshold && currentSize < threshold);
    }

    private static boolean deltaExceedsThreshold(int previousSize, int currentSize, int threshold) {
        return Math.abs((long) currentSize - previousSize) > threshold;
    }

    private int getEffectiveLargeOrderThreshold() {
        int thresholdFloor = getAbsoluteLargeOrderThreshold();
        if (largestOrderRank <= 0 || totalLevels < largestOrderRank) {
            return capOrderChangeThreshold(thresholdFloor);
        }
        return capOrderChangeThreshold(
                Math.max(thresholdFloor, getNthLargestSize(largestOrderRank)));
    }

    private static int capOrderChangeThreshold(int threshold) {
        return Math.min(MAX_ORDER_CHANGE_THRESHOLD, threshold);
    }

    private int getAbsoluteLargeOrderThreshold() {
        try {
            return Math.max(0, largeOrderThresholdSupplier.getAsInt());
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private int getNthLargestSize(int rank) {
        int remainingRank = rank;
        for (Map.Entry<Integer, Integer> entry : sizeCounts.descendingMap().entrySet()) {
            if (remainingRank <= entry.getValue()) {
                return entry.getKey();
            }
            remainingRank -= entry.getValue();
        }
        return 0;
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
        sizeCounts.merge(size, 1, Integer::sum);
        totalLevels++;
    }

    private void decrementSizeCount(int size) {
        sizeCounts.computeIfPresent(size, (ignored, count) -> count > 1 ? count - 1 : null);
    }

    private int sumMatchingTradeSize(LevelKey key, long fromMs, long toMs) {
        int total = 0;
        for (TradeRecord trade : recentTrades) {
            if (trade.observedAtMs < fromMs || trade.observedAtMs > toMs
                    || trade.priceTick != key.priceTick) {
                continue;
            }
            if ((key.bid && !trade.bidAggressor) || (!key.bid && trade.bidAggressor)) {
                total += trade.size;
            }
        }
        return total;
    }

    private int sumSamePriceTradeSize(int priceTick, long fromMs, long toMs) {
        int total = 0;
        for (TradeRecord trade : recentTrades) {
            if (trade.observedAtMs >= fromMs && trade.observedAtMs <= toMs
                    && trade.priceTick == priceTick) {
                total += trade.size;
            }
        }
        return total;
    }

    private void cleanupRecentTrades(long nowMs) {
        Iterator<TradeRecord> iterator = recentTrades.iterator();
        while (iterator.hasNext()) {
            if (nowMs - iterator.next().observedAtMs > TRADE_RETENTION_MS) {
                iterator.remove();
            } else {
                break;
            }
        }
    }

    private boolean isWallBreakAlertEnabled(LevelKey key) {
        try {
            return wallBreakAlertEnabled.test(key.bid);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static double getDayLevel(DoubleSupplier supplier) {
        try {
            return supplier.getAsDouble();
        } catch (RuntimeException ignored) {
            return Double.NaN;
        }
    }

    private static final class EvaluatedChange {
        private final LevelKey key;
        private final PendingChange pending;
        private final int currentSize;
        private final int delta;
        private final boolean crossedThreshold;
        private final boolean deltaExceedsThreshold;
        private final boolean tradeConsumption;
        private final int tradedSize;

        private EvaluatedChange(LevelKey key, PendingChange pending, int currentSize, int delta,
                                boolean crossedThreshold, boolean deltaExceedsThreshold,
                                boolean tradeConsumption, int tradedSize) {
            this.key = key;
            this.pending = pending;
            this.currentSize = currentSize;
            this.delta = delta;
            this.crossedThreshold = crossedThreshold;
            this.deltaExceedsThreshold = deltaExceedsThreshold;
            this.tradeConsumption = tradeConsumption;
            this.tradedSize = tradedSize;
        }
    }

    private static final class MoveMatch {
        private final PendingChange pending;
        private final EvaluatedChange change;

        private MoveMatch(PendingChange pending, EvaluatedChange change) {
            this.pending = pending;
            this.change = change;
        }
    }

    private static final class PendingChange {
        private final int previousSize;
        private final long createdAtMs;
        private long latestEventTimeNs;

        private PendingChange(int previousSize, long latestEventTimeNs, long createdAtMs) {
            this.previousSize = previousSize;
            this.latestEventTimeNs = latestEventTimeNs;
            this.createdAtMs = createdAtMs;
        }
    }

    private static final class TradeRecord {
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

    private static final class LevelKey {
        private final boolean bid;
        private final int priceTick;

        private LevelKey(boolean bid, int priceTick) {
            this.bid = bid;
            this.priceTick = priceTick;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof LevelKey)) {
                return false;
            }
            LevelKey levelKey = (LevelKey) other;
            return bid == levelKey.bid && priceTick == levelKey.priceTick;
        }

        @Override
        public int hashCode() {
            return Objects.hash(bid, priceTick);
        }
    }
}
