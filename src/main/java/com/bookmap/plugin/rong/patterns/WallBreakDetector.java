package com.bookmap.plugin.rong.patterns;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import com.bookmap.plugin.rong.signal.SignalComposerConfig;

/** One consumed-wall break rule, mirrored for bids and offers. */
public final class WallBreakDetector {
    private static final class Pending {
        final EventTimeWallTracker.Clear clear;
        final PatternEvent.Evidence evidence;
        final long epoch;
        Pending(EventTimeWallTracker.Clear clear, PatternEvent.Evidence evidence, long epoch) {
            this.clear = clear; this.evidence = evidence; this.epoch = epoch;
        }
    }
    private final String alias;
    private final double pips;
    private final SignalComposerConfig config;
    private final EventTimeTradeAttribution trades;
    private final Consumer<PatternEvent> output;
    private final List<Pending> pending = new ArrayList<>();
    public WallBreakDetector(String alias, double pips, SignalComposerConfig config,
            EventTimeTradeAttribution trades, Consumer<PatternEvent> output) {
        this.alias = alias; this.pips = pips; this.config = config; this.trades = trades; this.output = output;
    }
    public void onClear(EventTimeWallTracker.Clear clear, PatternEvent.Evidence evidence, long epoch) {
        if (clear.removedSize <= 0 || evidence.coverage != PatternEvent.Coverage.USABLE
                || evidence.attribution != PatternEvent.Attribution.PROBABLE_CONSUMPTION) return;
        pending.add(new Pending(clear, evidence, epoch));
        while (pending.size() > config.maxWallPhases) pending.remove(0);
        onTime(clear.observedAtNs);
    }
    public void onTime(long nowNs) {
        Iterator<Pending> iterator = pending.iterator();
        while (iterator.hasNext()) {
            Pending item = iterator.next();
            long printNs = trades.firstBreakout(item.clear, !item.clear.bid, config.detectors.breakoutDistanceTicks);
            if (printNs > 0) {
                PatternEventType type = item.clear.bid ? PatternEventType.BID_BREAKDOWN : PatternEventType.OFFER_BREAKOUT;
                output.accept(PatternEvent.builder(alias, item.epoch, type, item.clear.phaseId)
                        .size(item.clear.removedSize, PatternEvent.SizeBasis.DISPLAYED_REMOVED)
                        .price(item.clear.priceTick, pips).times(printNs, nowNs).evidence(item.evidence).build());
                iterator.remove();
            } else if (nowNs - item.clear.occurrenceNs > config.detectors.breakoutWindowMs * 1_000_000L) iterator.remove();
        }
    }
    public void reset() { pending.clear(); }
}
