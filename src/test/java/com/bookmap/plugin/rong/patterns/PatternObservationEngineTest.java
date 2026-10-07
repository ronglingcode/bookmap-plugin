package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.signal.SignalComposerConfig;

class PatternObservationEngineTest {
    @Test void bidWithdrawalIsTrackedAtInclusiveThreeThousandFloorButNotBelowIt() {
        for (long size : new long[] {999, 1000, 2999, 3000}) {
            Fixture f = new Fixture(); f.trade(5090, 1, false, 100);
            f.depth(true, 5100, size, 2000); f.time(2500);
            f.depth(true, 5100, 0, 2600); f.time(3100);
            if (size < 3000) assertTrue(f.events.isEmpty());
            else {
                assertEquals(1, f.events.size());
                assertEquals(PatternEventType.BIDS_CANCELLED, f.events.get(0).type);
                assertEquals(size, f.events.get(0).size);
                assertEquals(PatternSizeCategory.classify(size), f.events.get(0).sizeCategory);
            }
        }
    }
    @Test void offerHoldAndGrowthRequireThreeThousand() {
        for (long size : new long[] {1000, 2999, 3000}) {
            Fixture f = new Fixture(); f.trade(5090, 1, true, 100);
            f.depth(false, 5120, size, 2000); f.time(2500);
            f.trade(5119, 1, true, 2600); f.trade(5113, 1, false, 2700); f.trade(5113, 1, false, 3200);
            if (size < 3000) assertTrue(f.events.isEmpty());
            else assertEquals(PatternEventType.OFFER_HOLD, f.events.get(0).type);
            f.depth(false, 5120, 4000, 3300);
            assertTrue(f.events.stream().anyMatch(event -> event.type == PatternEventType.OFFER_SIZE_INCREASE
                    && event.sizeCategory == PatternSizeCategory.BELOW_NORMAL));
        }
    }
    @Test void smallConsumptionPatternsAreObservedOnBothSides() {
        for (boolean bid : new boolean[] {true, false}) {
            Fixture f = new Fixture(); f.trade(5090, 1, false, 100);
            int price = bid ? 5100 : 5120;
            f.depth(bid, price, 1000, 2000); f.time(2500);
            f.trade(price, 1000, !bid, 2600); f.depth(bid, price, 0, 2600);
            f.trade(price + (bid ? -1 : 1), 1, !bid, 2700); f.time(3100);
            assertEquals(1, f.events.size());
            assertEquals(bid ? PatternEventType.BID_BREAKDOWN : PatternEventType.OFFER_BREAKOUT, f.events.get(0).type);
            assertEquals(PatternSizeCategory.LEAST_SIGNIFICANT, f.events.get(0).sizeCategory);
        }
    }
    @Test void smallStepRevisionRetainsIdentityWhenSizeCrossesThreeThousand() {
        Fixture f = new Fixture(true); f.trade(5090, 1, false, 100);
        f.depth(true, 5100, 1000, 2000); f.time(2500);
        f.depth(true, 5101, 1000, 2600); f.time(3100);
        assertTrue(f.events.isEmpty());
        f.depth(true, 5101, 3000, 3200); f.time(3700);
        PatternEvent revised = f.events.get(f.events.size() - 1);
        assertEquals(PatternEventType.BID_STEP_UP, revised.type);
        assertEquals(1, revised.revision);
        assertEquals(PatternSizeCategory.BELOW_NORMAL, revised.sizeCategory);
    }
    static final long BASE = Instant.parse("2026-10-06T14:00:00Z").getEpochSecond() * 1_000_000_000L;
    static final PatternEvent.TimestampProvenance MARKET = PatternEvent.TimestampProvenance.MARKET;
    static class Fixture {
        final List<PatternEvent> events = new ArrayList<>();
        final PatternObservationEngine engine = new PatternObservationEngine("TEST", .01, SignalComposerConfig.defaults(),
                events::add, (reason, epoch) -> {}, diagnostic -> {});
        Fixture() { this(false); }
        Fixture(boolean seedRange) {
            engine.markReady(); time(0); engine.onBbo(5100, 5121, BASE, MARKET);
            if (seedRange) { trade(5080, 1, false, 0); trade(5140, 1, true, 0); }
        }
        void time(long ms) { engine.onTimestamp(BASE + ms * 1_000_000L, MARKET); }
        void depth(boolean bid, int price, long size, long ms) { engine.onDepth(bid, price, size, BASE + ms * 1_000_000L, MARKET); }
        void trade(int price, long size, Boolean buy, long ms) { engine.onTrade(price, size, buy, BASE + ms * 1_000_000L, MARKET); }
    }
    @Test void independentDefinitionsObserveBidReappearAndStepWithSharedInteraction() {
        Fixture f = new Fixture(true); f.trade(5090, 1, false, 100);
        f.depth(true, 5100, 3000, 2000); f.time(2500);
        f.trade(5100, 3000, false, 2600); f.depth(true, 5100, 0, 2600); f.time(3100);
        f.depth(true, 5101, 4000, 3200); f.time(3700);
        assertEquals(2, f.events.size());
        assertEquals(PatternEventType.BID_REAPPEAR, f.events.get(0).type);
        assertEquals(PatternEventType.BID_STEP_UP, f.events.get(1).type);
        assertEquals(f.events.get(0).interactionId, f.events.get(1).interactionId);
        assertEquals(4000, f.events.get(0).size);
        f.depth(true, 5101, 4500, 3900); f.time(4200);
        PatternEvent revised = f.events.get(2);
        assertEquals(f.events.get(1).id, revised.id); assertEquals(2, revised.revision);
        assertEquals(4500, revised.size); assertEquals(f.events.get(1).eventTimeNs, revised.eventTimeNs);
        assertEquals(BASE + 4200_000_000L, revised.observedAtNs);
    }
    @Test void independentDefinitionsObserveOfferReappearAndStepDown() {
        Fixture f = new Fixture(true); f.trade(5130, 1, true, 100);
        f.depth(false, 5120, 6000, 2000); f.time(2500);
        f.trade(5120, 6000, true, 2600); f.depth(false, 5120, 0, 2600); f.time(3100);
        f.depth(false, 5119, 3000, 3200); f.time(3700);
        assertEquals(2, f.events.size()); assertEquals(PatternEventType.OFFER_REAPPEAR, f.events.get(0).type);
        assertEquals(PatternEventType.OFFER_STEP_DOWN, f.events.get(1).type);
        assertEquals(3000, f.events.get(0).size); assertEquals(PatternMeaning.OFFER_BEARISH_CONFIRMATION, f.events.get(1).meaning);
    }
    @Test void absentReplacementCannotEmitDelayedStepUpdateAndReadinessIsRequired() {
        Fixture f = new Fixture(true); f.trade(5090, 1, false, 100);
        f.depth(true, 5100, 6000, 2000); f.time(2500);
        f.depth(true, 5101, 3000, 2600); f.time(3100); assertEquals(1, f.events.size());
        f.depth(true, 5101, 0, 3200); f.time(3600); assertEquals(1, f.events.size());
        f.engine.reset(com.bookmap.plugin.rong.signal.ResetReason.REPLAY_SEEK);
        f.depth(true, 5102, 6000, 4000); f.time(5000); assertEquals(1, f.events.size());
    }
}
