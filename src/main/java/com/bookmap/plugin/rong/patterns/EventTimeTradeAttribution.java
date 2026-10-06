package com.bookmap.plugin.rong.patterns;

import java.util.ArrayDeque;
import java.util.Deque;
import com.bookmap.plugin.rong.signal.SignalComposerConfig;

/** Bounded callback-volume evidence. Unknown aggressor and lost coverage are explicit. */
public final class EventTimeTradeAttribution {
    private static final class Trade {
        final int price; final long size, time; final Boolean buy;
        Trade(int price, long size, Boolean buy, long time) {
            this.price = price; this.size = size; this.buy = buy; this.time = time;
        }
    }
    public static final class Result {
        public final long matchingVolume;
        public final PatternEvent.Coverage coverage;
        public final PatternEvent.Attribution attribution;
        private Result(long volume, PatternEvent.Coverage coverage, PatternEvent.Attribution attribution) {
            matchingVolume = volume; this.coverage = coverage; this.attribution = attribution;
        }
        public PatternEvent.Evidence evidence(EventTimeWallTracker.Clear clear) {
            return PatternEvent.Evidence.builder().wall(clear.phaseId, clear.previousSize, clear.remainingSize)
                    .trades(matchingVolume, !clear.bid).attribution(attribution, coverage).build();
        }
    }
    private final SignalComposerConfig config;
    private final Deque<Trade> trades = new ArrayDeque<>();
    private long coverageStartNs, lostThroughNs, now;
    public EventTimeTradeAttribution(SignalComposerConfig config) { this.config = config; }
    public void markReady(long timeNs) {
        if (timeNs <= 0) throw new IllegalArgumentException("Market timestamp required");
        trades.clear(); coverageStartNs = timeNs; lostThroughNs = 0; now = timeNs;
    }
    public void onTrade(int price, long size, Boolean buyAggressor, long timeNs) {
        onTime(timeNs);
        if (price <= 0 || size <= 0) return;
        trades.addLast(new Trade(price, size, buyAggressor, timeNs));
        while (trades.size() > config.maxAttributionTrades) lostThroughNs = Math.max(lostThroughNs, trades.removeFirst().time);
    }
    public void onTime(long timeNs) {
        if (timeNs <= 0 || timeNs < now) throw new IllegalArgumentException("Reset attribution before backwards time");
        now = timeNs;
        // Keep evidence across stable-clear decisions and delayed breakout completion.
        long retention = (config.detectors.attributionLookbackMs + config.detectors.clearDecisionMs
                + config.detectors.breakoutWindowMs) * 1_000_000L;
        while (!trades.isEmpty() && trades.peekFirst().time < now - retention) trades.removeFirst();
    }
    public Result attribute(EventTimeWallTracker.Clear clear) {
        long from = clear.occurrenceNs - config.detectors.attributionLookbackMs * 1_000_000L;
        PatternEvent.Coverage coverage = coverageStartNs == 0 || from < coverageStartNs ? PatternEvent.Coverage.WARMUP
                : from <= lostThroughNs ? PatternEvent.Coverage.GAP : PatternEvent.Coverage.USABLE;
        long volume = 0;
        for (Trade trade : trades) {
            if (trade.time < from || trade.time > clear.observedAtNs || trade.price != clear.priceTick) continue;
            if (trade.buy == null) { coverage = PatternEvent.Coverage.GAP; continue; }
            if (trade.buy == !clear.bid) {
                if (Long.MAX_VALUE - volume < trade.size) { volume = Long.MAX_VALUE; coverage = PatternEvent.Coverage.GAP; }
                else volume += trade.size;
            }
        }
        PatternEvent.Attribution attribution = PatternEvent.Attribution.UNKNOWN;
        if (coverage == PatternEvent.Coverage.USABLE && clear.removedSize > 0) {
            double ratio = (double)volume / clear.removedSize;
            if (ratio >= config.detectors.consumptionRatio) attribution = PatternEvent.Attribution.PROBABLE_CONSUMPTION;
            else if (ratio <= config.detectors.withdrawalMaxTradeRatio) attribution = PatternEvent.Attribution.INFERRED_WITHDRAWAL;
        }
        return new Result(volume, coverage, attribution);
    }
    public int size() { return trades.size(); }
    public void reset() { trades.clear(); coverageStartNs = 0; lostThroughNs = 0; now = 0; }
}
