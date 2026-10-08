package com.bookmap.plugin.rong.patterns;

import java.util.function.Consumer;

/** Qualified stable bid loss with explicit, conservative attribution. */
public final class BidFailureDetector {
    private final String alias;
    private final double pips;
    private final Consumer<PatternEvent> output;
    public BidFailureDetector(String alias, double pips, Consumer<PatternEvent> output) {
        this.alias = alias; this.pips = pips; this.output = output;
    }
    public void onClear(EventTimeWallTracker.Clear clear, PatternEvent.Evidence evidence, long epoch) {
        if (!clear.bid || clear.removedSize <= 0 || evidence.coverage != PatternEvent.Coverage.USABLE) return;
        if (evidence.attribution != PatternEvent.Attribution.INFERRED_WITHDRAWAL) return;
        output.accept(PatternEvent.builder(alias, epoch, PatternEventType.BIDS_CANCELLED, clear.phaseId)
                .size(clear.removedSize, PatternEvent.SizeBasis.DISPLAYED_REMOVED).price(clear.priceTick, pips)
                .times(clear.occurrenceNs, clear.observedAtNs).evidence(evidence).build());
    }
}
