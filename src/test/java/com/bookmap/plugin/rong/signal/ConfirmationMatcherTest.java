package com.bookmap.plugin.rong.signal;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.signal.CompositionModelsTest.event;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.patterns.*;

class ConfirmationMatcherTest {
    private final ConfirmationMatcher matcher = new ConfirmationMatcher(SignalComposerConfig.defaults());
    private final long t = 900_000_000_000L;
    private PatternEvent bid() { return event(PatternEventType.BIDS_CANCELLED, 3000, 5105, t, "bid"); }
    private PatternEvent offer(long time, int price) { return event(PatternEventType.OFFER_REJECTION, 60000, price, time, "offer:" + time + ":" + price); }
    @Test void acceptsBeforeAfterAndExactTimeBoundary() {
        assertEquals(1, matcher.find(bid(), List.of(offer(t - 300_000_000_000L, 5120)), t).size());
        assertTrue(matcher.find(bid(), List.of(offer(t - 300_000_000_001L, 5120)), t).isEmpty());
        List<ConfirmationMatch> after = matcher.find(bid(), List.of(offer(t + 300_000_000_000L, 5120)), t + 300_000_000_000L);
        assertEquals(1, after.size()); assertEquals(ConfirmationMatch.Ordering.AFTER, after.get(0).ordering);
        assertTrue(matcher.find(bid(), List.of(offer(t + 300_000_000_001L, 5120)), t + 300_000_000_001L).isEmpty());
    }
    @Test void priceDistanceAndDirectionalNoiseAreExplicit() {
        assertEquals(1, matcher.find(bid(), List.of(offer(t, 5125)), t).size());
        assertTrue(matcher.find(bid(), List.of(offer(t, 5126)), t).isEmpty());
        assertEquals(1, matcher.find(bid(), List.of(offer(t, 5103)), t).size());
        assertTrue(matcher.find(bid(), List.of(offer(t, 5102)), t).isEmpty());
    }
    @Test void matchesBullishOfferOnlyToLongBidBehavior() {
        PatternEvent longBid = event(PatternEventType.BID_STEP_UP, 3000, 5105, t, "long");
        PatternEvent bullish = event(PatternEventType.OFFER_BREAKOUT, 60000, 5110, t, "bullish");
        assertEquals(1, matcher.find(longBid, List.of(bullish), t).size());
        assertTrue(matcher.find(bid(), List.of(bullish), t).isEmpty());
        assertTrue(matcher.find(longBid, List.of(offer(t, 5110)), t).isEmpty());
    }
    @Test void excludesFutureUnobservedStaleAndForeignEvidence() {
        assertTrue(matcher.find(bid(), List.of(offer(t + 1, 5120)), t).isEmpty());
        assertTrue(matcher.find(bid(), List.of(offer(t - 1, 5120)), t + 300_000_000_000L).isEmpty());
        PatternEvent foreign = PatternEvent.builder("OTHER", 2, PatternEventType.OFFER_REJECTION, "w")
                .size(60000, PatternEvent.SizeBasis.DISPLAYED_WALL).price(5120, .01).times(t, t).build();
        assertTrue(matcher.find(bid(), List.of(foreign), t).isEmpty());
        PatternEvent warmup = PatternEvent.builder("TEST", 1, PatternEventType.OFFER_REJECTION, "w")
                .size(60000, PatternEvent.SizeBasis.DISPLAYED_WALL).price(5120, .01).times(t, t).build();
        assertTrue(matcher.find(bid(), List.of(warmup), t).isEmpty());
    }
}
