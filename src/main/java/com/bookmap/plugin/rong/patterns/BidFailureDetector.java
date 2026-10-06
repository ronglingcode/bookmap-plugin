package com.bookmap.plugin.rong.patterns;

import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import com.bookmap.plugin.rong.signal.SignalComposerConfig;

/** Qualified stable bid loss with explicit, conservative attribution. */
public final class BidFailureDetector {
    private final String alias;
    private final double pips;
    private final Consumer<PatternEvent> output;
    private final SignalComposerConfig config;
    private final EventTimeTradeAttribution trades;
    private final List<Pending> pending = new ArrayList<>();
    private static final class Pending {
        final EventTimeWallTracker.Clear clear; final PatternEvent.Evidence evidence; final long epoch;
        Pending(EventTimeWallTracker.Clear clear, PatternEvent.Evidence evidence, long epoch) {
            this.clear = clear; this.evidence = evidence; this.epoch = epoch;
        }
    }
    public BidFailureDetector(String alias, double pips, SignalComposerConfig config,
            EventTimeTradeAttribution trades, Consumer<PatternEvent> output) {
        this.alias = alias; this.pips = pips; this.output = output;
        this.config = config; this.trades = trades;
    }
    public void onClear(EventTimeWallTracker.Clear clear, PatternEvent.Evidence evidence, long epoch) {
        if (!clear.bid || clear.removedSize <= 0 || evidence.coverage != PatternEvent.Coverage.USABLE) return;
        if (evidence.attribution == PatternEvent.Attribution.PROBABLE_CONSUMPTION) {
            pending.add(new Pending(clear, evidence, epoch));
            while (pending.size() > config.maxWallPhases) pending.remove(0);
            onTime(clear.observedAtNs); return;
        }
        if (evidence.attribution != PatternEvent.Attribution.INFERRED_WITHDRAWAL) return;
        output.accept(PatternEvent.builder(alias, epoch, PatternEventType.BIDS_CANCELLED, clear.phaseId)
                .size(clear.removedSize, PatternEvent.SizeBasis.DISPLAYED_REMOVED).price(clear.priceTick, pips)
                .times(clear.occurrenceNs, clear.observedAtNs).evidence(evidence).build());
    }
    public void onTime(long nowNs) {
        Iterator<Pending> iterator = pending.iterator();
        while (iterator.hasNext()) {
            Pending item = iterator.next();
            long printNs = trades.firstBreakout(item.clear, false, config.detectors.breakoutDistanceTicks);
            if (printNs > 0) {
                output.accept(PatternEvent.builder(alias, item.epoch, PatternEventType.BID_BREAKDOWN, item.clear.phaseId)
                        .size(item.clear.removedSize, PatternEvent.SizeBasis.DISPLAYED_REMOVED).price(item.clear.priceTick, pips)
                        .times(printNs, nowNs).evidence(item.evidence).build()); iterator.remove();
            } else if (nowNs - item.clear.occurrenceNs > config.detectors.breakoutWindowMs * 1_000_000L) iterator.remove();
        }
    }
    public void reset() { pending.clear(); }
}
