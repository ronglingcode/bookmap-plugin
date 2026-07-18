package com.bookmap.plugin.rong.patterns;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

import com.bookmap.plugin.rong.OrderBookState;
import com.bookmap.plugin.rong.pricelines.PriceLine;
import com.bookmap.plugin.rong.pricelines.PriceLineStore;
import com.bookmap.plugin.rong.pricelines.PriceZone;
import com.bookmap.plugin.rong.pricelines.PriceZoneStore;

import velox.api.layer1.data.TradeInfo;

/**
 * Event-time, display-only orchestrator for Bookmap wall patterns.
 *
 * <p>This class deliberately has no dependency on signal broadcasts, hotkeys, trade buttons,
 * or order APIs. Its only output is an immutable {@link BookmapPatternSignal}.</p>
 */
public final class BookmapPatternEngine implements PatternRuntimeContext, PatternScoringContext {

    private static final long NS_PER_MS = 1_000_000L;
    private static final long NS_PER_SECOND = 1_000_000_000L;
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime REGULAR_OPEN = LocalTime.of(9, 30);
    private static final LocalTime REGULAR_CLOSE = LocalTime.of(16, 0);
    private static final long MIN_WALL_LIFETIME_MS = 500;
    private static final long CLEAR_DECISION_DELAY_MS = 500;
    private static final long ATTRIBUTION_WINDOW_MS = 2_000;
    private static final double CLEAR_REMAINING_RATIO = 0.10;
    private static final double CLEAR_ATTRIBUTION_RATIO = 0.70;
    private static final long SWEEP_WINDOW_MS = 1_000;

    private final String instrumentAlias;
    private final double pips;
    private final IntSupplier wallThresholdFloor;
    private final double wallPercentile;
    private final OrderBookState orderBook;
    private final PriceLineStore priceLineStore;
    private final PriceZoneStore priceZoneStore;
    private final PatternEligibility eligibility;
    private final Consumer<BookmapPatternSignal> signalConsumer;
    private final List<PatternDefinition> definitions = new ArrayList<>();
    private final Map<PatternType, PatternDefinition> definitionsByType =
            new EnumMap<>(PatternType.class);
    private final BookmapPatternScorer scorer = new BookmapPatternScorer();
    private final Map<LevelKey, WallPhase> wallPhases = new LinkedHashMap<>();
    private final Deque<RecentTrade> recentTrades = new ArrayDeque<>();
    private final Deque<ClearedWall> recentClears = new ArrayDeque<>();
    private final Map<String, Boolean> alignmentCache = new HashMap<>();
    private final Map<String, Integer> obstacleCache = new HashMap<>();

    private boolean ready;
    private boolean regularSession;
    private boolean lifecycleSeededForSession;
    private LocalDate sessionDate;
    private long currentEventNs;
    private long currentEventMs;
    private long phaseSequence;
    private int bestBidTick;
    private int bestAskTick;
    private int lastTradeTick;
    private int sessionHighTick;
    private int sessionLowTick;
    private double regularVolume;
    private double regularPriceVolume;

    public BookmapPatternEngine(
            String instrumentAlias,
            double pips,
            IntSupplier wallThresholdFloor,
            double wallPercentile,
            OrderBookState orderBook,
            PriceLineStore priceLineStore,
            PriceZoneStore priceZoneStore,
            PatternEligibility eligibility,
            Consumer<BookmapPatternSignal> signalConsumer) {
        this.instrumentAlias = Objects.requireNonNull(instrumentAlias, "instrumentAlias");
        this.pips = pips;
        this.wallThresholdFloor = Objects.requireNonNull(wallThresholdFloor, "wallThresholdFloor");
        this.wallPercentile = wallPercentile;
        this.orderBook = Objects.requireNonNull(orderBook, "orderBook");
        this.priceLineStore = Objects.requireNonNull(priceLineStore, "priceLineStore");
        this.priceZoneStore = Objects.requireNonNull(priceZoneStore, "priceZoneStore");
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
        this.signalConsumer = Objects.requireNonNull(signalConsumer, "signalConsumer");
        definitions.add(new WallBreakPatternDefinition(PatternType.OFFER_WALL_BREAKOUT, false));
        definitions.add(new WallBreakPatternDefinition(PatternType.BID_WALL_BREAKDOWN, true));
        definitions.add(new ReappearPatternDefinition(PatternType.OFFER_REAPPEAR, false));
        definitions.add(new ReappearPatternDefinition(PatternType.BID_REAPPEAR, true));
        definitions.add(new StepPatternDefinition(PatternType.OFFER_STEP_DOWN, false));
        definitions.add(new StepPatternDefinition(PatternType.BID_STEP_UP, true));
        definitions.add(new VShapePatternDefinition(PatternType.OFFER_V_SHAPE_REJECTION, false));
        definitions.add(new VShapePatternDefinition(PatternType.BID_V_SHAPE_RECOVERY, true));
        for (PatternDefinition definition : definitions) {
            definitionsByType.put(definition.type(), definition);
        }
    }

    /** Arms the engine only after Bookmap's initial depth snapshot is complete. */
    public synchronized void markReady() {
        if (ready) return;
        ready = true;
        resetPatternLifecycle();
        if (regularSession) seedCurrentWalls();
    }

    public synchronized void shutdown() {
        ready = false;
        resetPatternLifecycle();
        recentTrades.clear();
        recentClears.clear();
    }

    /** Clears all event-derived state when automation is toggled without processing disabled events. */
    public synchronized void resetForFeatureToggle() {
        resetPatternLifecycle();
        recentTrades.clear();
        recentClears.clear();
        sessionDate = null;
        regularSession = false;
        lifecycleSeededForSession = false;
        bestBidTick = 0;
        bestAskTick = 0;
        lastTradeTick = 0;
        sessionHighTick = 0;
        sessionLowTick = 0;
        regularVolume = 0;
        regularPriceVolume = 0;
    }

    /** Called after the shared OrderBookState has received this absolute-size update. */
    public synchronized void onDepth(boolean bid, int priceTick, int size, long eventTimeNs) {
        beginEvent(eventTimeNs);
        if (!canTrackPatterns()) return;
        LevelKey key = new LevelKey(bid, priceTick);
        WallPhase phase = wallPhases.get(key);
        int threshold = effectiveThreshold();

        if (phase == null) {
            if (size > 0 && size >= threshold) {
                wallPhases.put(key, newPhase(bid, priceTick, size, threshold));
            }
            advanceLifecycle();
            dispatchTime();
            return;
        }

        phase.currentSize = Math.max(0, size);
        phase.peakSize = Math.max(phase.peakSize, phase.currentSize);
        if (!phase.qualified && phase.currentSize < phase.effectiveThreshold) {
            wallPhases.remove(key);
            advanceLifecycle();
            dispatchTime();
            return;
        }
        if (phase.pendingClearAtMs > 0 && phase.currentSize > phase.peakSize * CLEAR_REMAINING_RATIO) {
            phase.pendingClearAtMs = 0;
            phase.tradedAtPending = 0;
        }
        if (phase.qualified && phase.pendingClearAtMs == 0
                && phase.currentSize <= phase.peakSize * CLEAR_REMAINING_RATIO) {
            phase.pendingClearAtMs = currentEventMs;
            phase.pendingClearAtNs = currentEventNs;
            phase.highAtPending = sessionHighTick;
            phase.lowAtPending = sessionLowTick;
            phase.tradedAtPending = matchingAggressorVolume(phase, currentEventMs);
        }
        advanceLifecycle();
        dispatchTime();
    }

    public synchronized void onTrade(double priceTicks, int size, TradeInfo tradeInfo, long eventTimeNs) {
        onTradeInternal(
                (int) Math.round(priceTicks), size,
                tradeInfo != null && tradeInfo.isBidAggressor,
                tradeInfo != null,
                eventTimeNs);
    }

    /** Testable overload that does not require construction of Bookmap API data objects. */
    public synchronized void onTrade(
            int priceTick, int size, boolean bidAggressor, long eventTimeNs) {
        onTradeInternal(priceTick, size, bidAggressor, true, eventTimeNs);
    }

    private void onTradeInternal(
            int priceTick, int size, boolean bidAggressor, boolean aggressorKnown, long eventTimeNs) {
        beginEvent(eventTimeNs);
        if (!regularSession || size <= 0 || priceTick <= 0) return;

        lastTradeTick = priceTick;
        regularPriceVolume += (double) priceTick * size;
        regularVolume += size;

        if (!ready) {
            if (sessionHighTick == 0 || priceTick > sessionHighTick) sessionHighTick = priceTick;
            if (sessionLowTick == 0 || priceTick < sessionLowTick) sessionLowTick = priceTick;
            return;
        }

        recentTrades.addLast(new RecentTrade(
                priceTick, size, bidAggressor, aggressorKnown, currentEventMs));
        pruneRecentTrades(currentEventMs);

        advanceLifecycle();
        PatternTradeTick tick = new PatternTradeTick(
                priceTick, size, bidAggressor, currentEventNs, currentEventMs,
                sessionHighTick, sessionLowTick);
        for (PatternDefinition definition : definitions) {
            if (eligibility.isEnabled(definition.type())) definition.onTrade(tick, this);
        }

        // Intentionally update after pattern evaluation. This lets the trade that crosses the
        // wall-clear HOD/LOD compare against the pre-trade session snapshot.
        if (sessionHighTick == 0 || priceTick > sessionHighTick) sessionHighTick = priceTick;
        if (sessionLowTick == 0 || priceTick < sessionLowTick) sessionLowTick = priceTick;
        dispatchTime();
    }

    public synchronized void onBbo(
            int bidPrice, int bidSize, int askPrice, int askSize, long eventTimeNs) {
        beginEvent(eventTimeNs);
        bestBidTick = bidPrice;
        bestAskTick = askPrice;
        if (!canTrackPatterns()) return;
        advanceLifecycle();
        for (PatternDefinition definition : definitions) {
            if (eligibility.isEnabled(definition.type())) definition.onBbo(this);
        }
    }

    public synchronized void onTimestamp(long eventTimeNs) {
        beginEvent(eventTimeNs);
        if (!canTrackPatterns()) return;
        advanceLifecycle();
        dispatchTime();
    }

    private void beginEvent(long eventTimeNs) {
        currentEventNs = eventTimeNs > 0 ? eventTimeNs : System.currentTimeMillis() * NS_PER_MS;
        currentEventMs = currentEventNs / NS_PER_MS;
        alignmentCache.clear();
        obstacleCache.clear();

        ZonedDateTime time = toInstant(currentEventNs).atZone(NEW_YORK);
        LocalDate eventDate = time.toLocalDate();
        if (sessionDate == null || !sessionDate.equals(eventDate)) {
            sessionDate = eventDate;
            sessionHighTick = 0;
            sessionLowTick = 0;
            regularVolume = 0;
            regularPriceVolume = 0;
            lifecycleSeededForSession = false;
            resetPatternLifecycle();
        }
        boolean nowRegular = !time.toLocalTime().isBefore(REGULAR_OPEN)
                && time.toLocalTime().isBefore(REGULAR_CLOSE);
        if (nowRegular && !regularSession) {
            resetPatternLifecycle();
            lifecycleSeededForSession = false;
        }
        regularSession = nowRegular;
        if (canTrackPatterns() && !lifecycleSeededForSession) seedCurrentWalls();
    }

    private boolean canTrackPatterns() {
        return ready && regularSession;
    }

    private void seedCurrentWalls() {
        lifecycleSeededForSession = true;
        int threshold = effectiveThreshold();
        seedSide(true, threshold);
        seedSide(false, threshold);
    }

    private void seedSide(boolean bid, int threshold) {
        for (Map.Entry<Integer, Integer> level : orderBook.getLevelsSnapshot(bid).entrySet()) {
            if (level.getValue() >= threshold) {
                LevelKey key = new LevelKey(bid, level.getKey());
                wallPhases.putIfAbsent(key, newPhase(bid, level.getKey(), level.getValue(), threshold));
            }
        }
    }

    private WallPhase newPhase(boolean bid, int priceTick, int size, int threshold) {
        return new WallPhase(
                instrumentAlias + ":" + (bid ? "B" : "A") + ":" + priceTick + ":" + (++phaseSequence),
                bid, priceTick, size, threshold, currentEventMs);
    }

    private void advanceLifecycle() {
        List<WallPhase> qualified = new ArrayList<>();
        List<WallPhase> cleared = new ArrayList<>();
        List<LevelKey> removed = new ArrayList<>();
        for (Map.Entry<LevelKey, WallPhase> entry : wallPhases.entrySet()) {
            WallPhase phase = entry.getValue();
            if (!phase.qualified && phase.currentSize >= phase.effectiveThreshold
                    && currentEventMs - phase.firstSeenMs >= MIN_WALL_LIFETIME_MS) {
                phase.qualified = true;
                phase.qualifiedAtMs = phase.firstSeenMs + MIN_WALL_LIFETIME_MS;
                qualified.add(phase);
            }
            if (phase.pendingClearAtMs > 0) {
                int attributed = Math.max(
                        phase.tradedAtPending,
                        matchingAggressorVolume(phase, phase.pendingClearAtMs));
                int displayedDecrease = Math.max(0, phase.peakSize - phase.currentSize);
                boolean consumed = phase.currentSize <= phase.peakSize * CLEAR_REMAINING_RATIO
                        && attributed >= displayedDecrease * CLEAR_ATTRIBUTION_RATIO;
                if (consumed) {
                    phase.tradedAtPending = attributed;
                    cleared.add(phase);
                    removed.add(entry.getKey());
                } else if (currentEventMs - phase.pendingClearAtMs >= CLEAR_DECISION_DELAY_MS) {
                    removed.add(entry.getKey());
                }
            }
        }
        for (WallPhase phase : qualified) dispatchQualified(phase.snapshot(0, 0, 0, false));
        for (WallPhase phase : cleared) dispatchCleared(phase);
        for (LevelKey key : removed) wallPhases.remove(key);
    }

    private void dispatchQualified(WallSnapshot wall) {
        for (PatternDefinition definition : definitions) {
            if (eligibility.isEnabled(definition.type())) definition.onWallQualified(wall, this);
        }
    }

    private void dispatchCleared(WallPhase phase) {
        pruneRecentClears(phase.pendingClearAtMs);
        int nearTicks = nearDistanceTicks(phase.priceTick);
        int stacked = 1;
        for (ClearedWall previous : recentClears) {
            if (previous.bid == phase.bid
                    && Math.abs(previous.priceTick - phase.priceTick) <= nearTicks) stacked++;
        }
        boolean sweep = stacked >= 3;
        recentClears.addLast(new ClearedWall(phase.bid, phase.priceTick, phase.pendingClearAtMs));
        WallSnapshot wall = phase.snapshot(
                phase.pendingClearAtMs,
                phase.highAtPending,
                phase.lowAtPending,
                sweep);
        for (PatternDefinition definition : definitions) {
            if (eligibility.isEnabled(definition.type())) definition.onWallCleared(wall, this);
        }
    }

    private void dispatchTime() {
        for (PatternDefinition definition : definitions) {
            if (eligibility.isEnabled(definition.type())) definition.onTime(this);
        }
    }

    private int matchingAggressorVolume(WallPhase phase, long clearTimeMs) {
        int volume = 0;
        long start = clearTimeMs - ATTRIBUTION_WINDOW_MS;
        long end = Math.min(currentEventMs, clearTimeMs + ATTRIBUTION_WINDOW_MS);
        for (RecentTrade trade : recentTrades) {
            if (trade.timeMs < start || trade.timeMs > end) continue;
            if (trade.priceTick != phase.priceTick) continue;
            if (!trade.aggressorKnown) continue;
            boolean matches = phase.bid ? !trade.bidAggressor : trade.bidAggressor;
            if (matches) volume += trade.size;
        }
        return volume;
    }

    private int effectiveThreshold() {
        return Math.max(1, Math.max(Math.max(0, wallThresholdFloor.getAsInt()),
                orderBook.getPercentileThreshold(wallPercentile)));
    }

    private void pruneRecentTrades(long nowMs) {
        long cutoff = nowMs - ATTRIBUTION_WINDOW_MS - CLEAR_DECISION_DELAY_MS;
        while (!recentTrades.isEmpty() && recentTrades.peekFirst().timeMs < cutoff) {
            recentTrades.removeFirst();
        }
    }

    private void pruneRecentClears(long nowMs) {
        while (!recentClears.isEmpty()
                && nowMs - recentClears.peekFirst().timeMs > SWEEP_WINDOW_MS) {
            recentClears.removeFirst();
        }
    }

    private void resetPatternLifecycle() {
        wallPhases.clear();
        recentClears.clear();
        for (PatternDefinition definition : definitions) definition.reset();
    }

    @Override
    public long nowMs() {
        return currentEventMs;
    }

    @Override
    public long nowNs() {
        return currentEventNs;
    }

    @Override
    public int bestBidTick() {
        return bestBidTick;
    }

    @Override
    public int bestAskTick() {
        return bestAskTick;
    }

    @Override
    public int lastTradeTick() {
        return lastTradeTick;
    }

    @Override
    public int sessionHighTick() {
        return sessionHighTick;
    }

    @Override
    public int sessionLowTick() {
        return sessionLowTick;
    }

    @Override
    public void emit(PatternCandidate candidate) {
        if (!ready || !regularSession || !eligibility.isEnabled(candidate.patternType)) return;
        PatternDefinition definition = definitionsByType.get(candidate.patternType);
        if (definition == null) return;
        BookmapPatternScorer.ScoreResult result = scorer.score(candidate, this, definition);
        BookmapPatternSignal signal = new BookmapPatternSignal(
                candidate.episodeKey,
                instrumentAlias,
                candidate.patternType,
                candidate.triggerPriceTick,
                candidate.triggerPriceTick * pips,
                candidate.referenceWall.priceTick,
                candidate.referenceWall.peakSize,
                result.score,
                result.contributions,
                candidate.eventTimeNs,
                System.currentTimeMillis());
        signalConsumer.accept(signal);
    }

    @Override
    public int currentPriceTick() {
        if (bestBidTick > 0 && bestAskTick > 0) return (bestBidTick + bestAskTick) / 2;
        int quote = Math.max(bestBidTick, bestAskTick);
        return quote > 0 ? quote : lastTradeTick;
    }

    @Override
    public double vwapTick() {
        return regularVolume > 0 ? regularPriceVolume / regularVolume : Double.NaN;
    }

    @Override
    public int nearDistanceTicks(int priceTick) {
        return Math.max(10, (int) Math.round(Math.abs(priceTick) * 0.0025));
    }

    @Override
    public int largestOpposingWallSize(
            Direction direction, int triggerPriceTick, int nearDistanceTicks) {
        String key = direction.name() + ':' + triggerPriceTick + ':' + nearDistanceTicks;
        Integer cached = obstacleCache.get(key);
        if (cached != null) return cached;
        boolean inspectBids = direction == Direction.SHORT;
        NavigableMap<Integer, Integer> levels = orderBook.getLevelsSnapshot(inspectBids);
        int largest = 0;
        for (Map.Entry<Integer, Integer> entry : levels.entrySet()) {
            int price = entry.getKey();
            boolean inDirection = inspectBids ? price < triggerPriceTick : price > triggerPriceTick;
            if (inDirection && Math.abs(price - triggerPriceTick) <= nearDistanceTicks) {
                largest = Math.max(largest, entry.getValue());
            }
        }
        obstacleCache.put(key, largest);
        return largest;
    }

    @Override
    public boolean alignsWithConfiguredLevel(int priceTick, int ignoredNearDistanceTicks) {
        String key = Integer.toString(priceTick);
        Boolean cached = alignmentCache.get(key);
        if (cached != null) return cached;
        final int lineToleranceTicks = 2;
        boolean aligned = false;
        for (PriceLine line : priceLineStore.getLines(instrumentAlias)) {
            if (line.getType() == PriceLine.LineType.ENTRY_ORDER
                    || line.getType() == PriceLine.LineType.EXIT_ORDER) continue;
            if (Math.abs(line.getPriceInTicks() - priceTick) <= lineToleranceTicks) {
                aligned = true;
                break;
            }
        }
        if (!aligned) {
            for (PriceZone zone : priceZoneStore.getZones(instrumentAlias)) {
                if (priceTick >= zone.getLowPriceInTicks() - lineToleranceTicks
                        && priceTick <= zone.getHighPriceInTicks() + lineToleranceTicks) {
                    aligned = true;
                    break;
                }
            }
        }
        alignmentCache.put(key, aligned);
        return aligned;
    }

    private static Instant toInstant(long timestampNs) {
        long seconds = Math.floorDiv(timestampNs, NS_PER_SECOND);
        long nanos = Math.floorMod(timestampNs, NS_PER_SECOND);
        return Instant.ofEpochSecond(seconds, nanos);
    }

    private static final class LevelKey {
        final boolean bid;
        final int priceTick;

        LevelKey(boolean bid, int priceTick) {
            this.bid = bid;
            this.priceTick = priceTick;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof LevelKey)) return false;
            LevelKey other = (LevelKey) object;
            return bid == other.bid && priceTick == other.priceTick;
        }

        @Override
        public int hashCode() {
            return 31 * Boolean.hashCode(bid) + priceTick;
        }
    }

    private static final class WallPhase {
        final String phaseId;
        final boolean bid;
        final int priceTick;
        final int initialSize;
        final int effectiveThreshold;
        final long firstSeenMs;
        int peakSize;
        int currentSize;
        boolean qualified;
        long qualifiedAtMs;
        long pendingClearAtMs;
        long pendingClearAtNs;
        int tradedAtPending;
        int highAtPending;
        int lowAtPending;

        WallPhase(String phaseId, boolean bid, int priceTick, int size,
                  int effectiveThreshold, long firstSeenMs) {
            this.phaseId = phaseId;
            this.bid = bid;
            this.priceTick = priceTick;
            this.initialSize = size;
            this.peakSize = size;
            this.currentSize = size;
            this.effectiveThreshold = effectiveThreshold;
            this.firstSeenMs = firstSeenMs;
        }

        WallSnapshot snapshot(long clearedAtMs, int highAtClear, int lowAtClear, boolean sweep) {
            return new WallSnapshot(
                    phaseId, bid, priceTick, initialSize, peakSize, currentSize,
                    firstSeenMs, qualifiedAtMs, clearedAtMs, tradedAtPending,
                    effectiveThreshold, highAtClear, lowAtClear, sweep);
        }
    }

    private static final class RecentTrade {
        final int priceTick;
        final int size;
        final boolean bidAggressor;
        final boolean aggressorKnown;
        final long timeMs;

        RecentTrade(int priceTick, int size, boolean bidAggressor,
                    boolean aggressorKnown, long timeMs) {
            this.priceTick = priceTick;
            this.size = size;
            this.bidAggressor = bidAggressor;
            this.aggressorKnown = aggressorKnown;
            this.timeMs = timeMs;
        }
    }

    private static final class ClearedWall {
        final boolean bid;
        final int priceTick;
        final long timeMs;

        ClearedWall(boolean bid, int priceTick, long timeMs) {
            this.bid = bid;
            this.priceTick = priceTick;
            this.timeMs = timeMs;
        }
    }
}
