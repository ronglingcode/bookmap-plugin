package com.bookmap.plugin.rong.patterns;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import com.bookmap.plugin.rong.signal.ResetReason;
import com.bookmap.plugin.rong.signal.SignalComposerConfig;

/** Canonical callback observer feeding SignalComposer; independent of execution. */
public final class PatternObservationEngine implements PatternRuntimeContext {
    private final SignalComposerConfig config;
    private final ObservationClock clock = new ObservationClock();
    private final EventTimeWallTracker walls;
    private final EventTimeTradeAttribution attribution;
    private final EventTimeRelocationTracker relocation;
    private final PatternEventNormalizer normalizer;
    private final BidFailureDetector bidFailures;
    private final WallBreakDetector wallBreaks;
    private final WallInteractionDetector offers, bids;
    private final Consumer<PatternEvent> output;
    private final BiConsumer<ResetReason, Long> resets;
    private final Consumer<String> diagnostics;
    private final List<PatternDefinition> definitions = new ArrayList<>();
    private final Map<String, Depth> snapshot = new LinkedHashMap<>();
    private boolean seeded;
    private String pendingCoverageGap;
    private int bid, ask, last, high, low, definitionObservations;
    private static final class Depth {
        final boolean bid; final int price; final long size;
        Depth(boolean bid, int price, long size) { this.bid = bid; this.price = price; this.size = size; }
    }
    public PatternObservationEngine(String alias, double pips, SignalComposerConfig config, Consumer<PatternEvent> output,
            BiConsumer<ResetReason, Long> resets, Consumer<String> diagnostics) {
        this.config = config; this.output = output; this.resets = resets; this.diagnostics = diagnostics;
        walls = new EventTimeWallTracker(clock.epoch(), config);
        attribution = new EventTimeTradeAttribution(config); relocation = new EventTimeRelocationTracker(config);
        normalizer = new PatternEventNormalizer(alias, pips, config);
        Consumer<PatternEvent> usableOutput = event -> {
            if (pendingCoverageGap == null && event.size >= event.type.minimumTrackedSize()) output.accept(event);
        };
        wallBreaks = new WallBreakDetector(alias, pips, config, attribution, usableOutput);
        bidFailures = new BidFailureDetector(alias, pips, usableOutput);
        offers = new WallInteractionDetector(false, alias, pips, config, walls, usableOutput);
        bids = new WallInteractionDetector(true, alias, pips, config, walls, usableOutput);
        definitions.add(new ReappearPatternDefinition(PatternType.BID_REAPPEAR, true));
        definitions.add(new ReappearPatternDefinition(PatternType.OFFER_REAPPEAR, false));
        definitions.add(new StepPatternDefinition(PatternType.BID_STEP_UP, true));
        definitions.add(new StepPatternDefinition(PatternType.OFFER_STEP_DOWN, false));
    }
    private boolean begin(long timeNs, PatternEvent.TimestampProvenance provenance) {
        ObservationClock.Tick tick = clock.advance(timeNs, provenance);
        if (tick.resetReason != null) {
            clearState(tick.epoch);
            if (tick.resetReason == ResetReason.REPLAY_SEEK) snapshot.clear();
            resets.accept(tick.resetReason, tick.epoch);
        }
        if (!tick.usable) return false;
        if (!seeded) {
            seeded = true; attribution.markReady(timeNs);
            for (Depth depth : snapshot.values()) walls.seed(depth.bid, depth.price, depth.size, timeNs);
        }
        return true;
    }
    public void markReady() { clock.markReady(); }
    public void onDepth(boolean isBid, int price, long size, long timeNs, PatternEvent.TimestampProvenance provenance) {
        boolean usable = begin(timeNs, provenance);
        if (provenance != PatternEvent.TimestampProvenance.MARKET || timeNs <= 0 || price <= 0 || size < 0) return;
        EventTimeWallTracker.Wall previous = walls.active(isBid, price);
        String key = (isBid ? "B:" : "O:") + price;
        if (size >= config.observationFloorSize) snapshot.put(key, new Depth(isBid, price, size)); else snapshot.remove(key);
        while (snapshot.size() > config.maxWallPhases) {
            coverageGap("Snapshot observation capacity overflow"); return;
        }
        if (!usable) return;
        relocation.onDepth(isBid, price, previous == null ? 0 : previous.size, size, timeNs);
        if (relocation.consumeOverflow()) { coverageGap("Relocation buffer overflow"); return; }
        attribution.onTime(timeNs); dispatch(walls.onDepth(isBid, price, size, timeNs)); dispatchTime();
    }
    public void onTrade(int price, long size, Boolean buyAggressor, long timeNs, PatternEvent.TimestampProvenance provenance) {
        if (!begin(timeNs, provenance) || price <= 0 || size <= 0) return;
        int previousHigh = high, previousLow = low;
        last = price; high = high == 0 ? price : Math.max(high, price); low = low == 0 ? price : Math.min(low, price);
        attribution.onTrade(price, size, buyAggressor, timeNs); relocation.onTime(timeNs);
        if (attribution.consumeOverflow()) { coverageGap("Trade attribution buffer overflow"); return; }
        dispatch(walls.onTime(timeNs));
        if (!clock.usable()) return;
        PatternTradeTick trade = new PatternTradeTick(price, safeInt(size), Boolean.TRUE.equals(buyAggressor), timeNs, nowMs(), previousHigh, previousLow);
        for (PatternDefinition definition : definitions) definition.onTrade(trade, this);
        offers.onTrade(price, timeNs, epoch());
        bids.onTrade(price, timeNs, epoch());
        dispatchTime();
    }
    public void onBbo(int bid, int ask, long timeNs, PatternEvent.TimestampProvenance provenance) {
        if (!begin(timeNs, provenance)) return;
        if (bid > 0 && ask > 0 && bid > ask) return;
        this.bid = bid; this.ask = ask; attribution.onTime(timeNs); relocation.onTime(timeNs);
        dispatch(walls.onTime(timeNs)); if (!clock.usable()) return;
        offers.onBbo(bid, ask); bids.onBbo(bid, ask);
        for (PatternDefinition definition : definitions) definition.onBbo(this); dispatchTime();
    }
    public void onTimestamp(long timeNs, PatternEvent.TimestampProvenance provenance) {
        if (!begin(timeNs, provenance)) return;
        attribution.onTime(timeNs); relocation.onTime(timeNs); dispatch(walls.onTime(timeNs)); dispatchTime();
    }
    private void dispatch(EventTimeWallTracker.Update update) {
        for (String diagnostic : update.diagnostics) {
            diagnostics.accept(diagnostic);
            if (diagnostic.startsWith("Wall phase capacity eviction")) { coverageGap("Wall phase capacity overflow"); return; }
        }
        for (EventTimeWallTracker.Clear clear : update.clears) {
            PatternEvent.Evidence evidence = attribution.attribute(clear).evidence(clear, relocation.match(clear));
            diagnostics.accept("Measured wall clear " + clear.phaseId + "; removed=" + clear.removedSize
                    + "; attribution=" + evidence.attribution + "; coverage=" + evidence.coverage);
            bidFailures.onClear(clear, evidence, epoch());
            wallBreaks.onClear(clear, evidence, epoch());
            if (evidence.coverage != PatternEvent.Coverage.USABLE || evidence.attribution != PatternEvent.Attribution.PROBABLE_CONSUMPTION) continue;
            boundDefinitions();
            WallSnapshot wall = new WallSnapshot(clear.phaseId, clear.bid, clear.priceTick, safeInt(clear.previousSize),
                    safeInt(clear.previousSize), safeInt(clear.remainingSize), 0, 0, clear.occurrenceNs / 1_000_000L,
                    safeInt(config.observationFloorSize), high, low);
            for (PatternDefinition definition : definitions) definition.onWallCleared(wall, this);
        }
        for (EventTimeWallTracker.Wall qualified : update.qualified) {
            boundDefinitions();
            WallSnapshot wall = new WallSnapshot(qualified.phaseId, qualified.bid, qualified.priceTick, safeInt(qualified.size),
                    safeInt(qualified.size), safeInt(qualified.size), qualified.firstSeenNs / 1_000_000L, nowMs(), 0,
                    safeInt(config.observationFloorSize), high, low);
            for (PatternDefinition definition : definitions) definition.onWallQualified(wall, this);
        }
    }
    private void boundDefinitions() {
        if (++definitionObservations <= config.maxWallPhases) return;
        for (PatternDefinition definition : definitions) definition.reset(); definitionObservations = 1;
        diagnostics.accept("Pattern reference capacity reset");
    }
    private void dispatchTime() {
        if (!clock.usable()) return;
        wallBreaks.onTime(nowNs());
        offers.onTime(nowNs(), epoch());
        bids.onTime(nowNs(), epoch());
        for (PatternDefinition definition : definitions) definition.onTime(this);
        if (pendingCoverageGap != null) coverageGap(pendingCoverageGap);
    }
    private static int safeInt(long value) { return (int)Math.min(Integer.MAX_VALUE, value); }
    private void clearState(long epoch) {
        walls.reset(epoch); attribution.reset(); relocation.reset(); normalizer.reset(); wallBreaks.reset(); offers.reset(); bids.reset();
        for (PatternDefinition definition : definitions) definition.reset();
        seeded = false; bid = ask = last = high = low = definitionObservations = 0;
        pendingCoverageGap = null;
    }
    public void reset(ResetReason reason) {
        ObservationClock.Tick tick = clock.reset(reason); clearState(tick.epoch); snapshot.clear(); resets.accept(reason, tick.epoch);
    }
    public long epoch() { return clock.epoch(); }
    public boolean usable() { return clock.usable(); }
    public String status() { return clock.status(); }
    public long nowMs() { return nowNs() / 1_000_000L; }
    public long nowNs() { return clock.watermarkNs(); }
    public int bestBidTick() { return bid; }
    public int bestAskTick() { return ask; }
    public int lastTradeTick() { return last; }
    public int sessionHighTick() { return high; }
    public int sessionLowTick() { return low; }
    public void emit(PatternCandidate candidate) {
        if (pendingCoverageGap != null) return;
        PatternEvent event = normalizer.normalize(candidate, walls, epoch(), nowNs());
        if (normalizer.consumeOverflow()) { pendingCoverageGap = "Normalized episode capacity overflow"; return; }
        if (event != null) output.accept(event);
    }
    private void coverageGap(String diagnostic) { diagnostics.accept(diagnostic + "; fresh readiness required"); reset(ResetReason.COVERAGE_GAP); }
}
