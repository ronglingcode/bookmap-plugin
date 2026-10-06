package com.bookmap.plugin.rong.signal;

import java.util.ArrayList;
import java.util.List;
import com.bookmap.plugin.rong.patterns.Direction;
import com.bookmap.plugin.rong.patterns.PatternEvent;
import com.bookmap.plugin.rong.patterns.PatternMeaning;

/** Local, already-observed complementary evidence; no score or total-size aggregation. */
public final class ConfirmationMatcher {
    private final SignalComposerConfig config;
    public ConfirmationMatcher(SignalComposerConfig config) { this.config = config; }

    public List<ConfirmationMatch> find(PatternEvent trigger, Iterable<PatternEvent> history, long processingNs) {
        Direction direction = TradingSignal.directionFrom(trigger);
        List<ConfirmationMatch> matches = new ArrayList<>();
        long beforeNs = config.beforeWindowMs * 1_000_000L, afterNs = config.afterWindowMs * 1_000_000L;
        if (trigger.observedAtNs > processingNs || processingNs - trigger.eventTimeNs > afterNs) return java.util.Collections.emptyList();
        PatternMeaning wanted = direction == Direction.LONG ? PatternMeaning.OFFER_BULLISH_CONFIRMATION : PatternMeaning.OFFER_BEARISH_CONFIRMATION;
        for (PatternEvent event : history) {
            if (event.meaning != wanted || !event.instrumentAlias.equals(trigger.instrumentAlias) || event.epoch != trigger.epoch
                    || Double.compare(event.tickSize, trigger.tickSize) != 0 || event.observedAtNs > processingNs
                    || event.timestampProvenance != PatternEvent.TimestampProvenance.MARKET
                    || event.evidence.coverage != PatternEvent.Coverage.USABLE) continue;
            long delta = event.eventTimeNs - trigger.eventTimeNs;
            if (delta < -beforeNs || delta > afterNs || processingNs - event.eventTimeNs > beforeNs) continue;
            long distance = (long)event.priceTick - trigger.priceTick;
            if (Math.abs(distance) > config.maxPriceDistanceTicks || distance < -config.directionalPriceToleranceTicks) continue;
            matches.add(new ConfirmationMatch(trigger, event));
        }
        return TradingSignal.immutable(matches);
    }
}
