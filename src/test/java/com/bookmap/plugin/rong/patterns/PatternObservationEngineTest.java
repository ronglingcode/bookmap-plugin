package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.signal.SignalComposerConfig;

class PatternObservationEngineTest {
    static final long BASE = Instant.parse("2026-10-06T14:00:00Z").getEpochSecond() * 1_000_000_000L;
    static final PatternEvent.TimestampProvenance MARKET = PatternEvent.TimestampProvenance.MARKET;
    static class Fixture {
        final List<PatternEvent> events = new ArrayList<>();
        final PatternObservationEngine engine = new PatternObservationEngine("TEST", .01, SignalComposerConfig.defaults(),
                events::add, (reason, epoch) -> {}, diagnostic -> {});
        Fixture() { engine.markReady(); time(0); engine.onBbo(5100, 5121, BASE, MARKET); }
        void time(long ms) { engine.onTimestamp(BASE + ms * 1_000_000L, MARKET); }
        void depth(boolean bid, int price, long size, long ms) { engine.onDepth(bid, price, size, BASE + ms * 1_000_000L, MARKET); }
        void trade(int price, long size, Boolean buy, long ms) { engine.onTrade(price, size, buy, BASE + ms * 1_000_000L, MARKET); }
    }
    @Test void independentDefinitionsObserveBidReappearAndStepWithSharedInteraction() {
        Fixture f = new Fixture(); f.trade(5090, 1, false, 100);
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
        Fixture f = new Fixture(); f.trade(5130, 1, true, 100);
        f.depth(false, 5120, 6000, 2000); f.time(2500);
        f.trade(5120, 6000, true, 2600); f.depth(false, 5120, 0, 2600); f.time(3100);
        f.depth(false, 5119, 3000, 3200); f.time(3700);
        assertEquals(2, f.events.size()); assertEquals(PatternEventType.OFFER_REAPPEAR, f.events.get(0).type);
        assertEquals(PatternEventType.OFFER_STEP_DOWN, f.events.get(1).type);
        assertEquals(3000, f.events.get(0).size); assertEquals(PatternMeaning.OFFER_BEARISH_CONFIRMATION, f.events.get(1).meaning);
    }
    @Test void absentReplacementCannotEmitDelayedStepUpdateAndReadinessIsRequired() {
        Fixture f = new Fixture(); f.trade(5090, 1, false, 100);
        f.depth(true, 5100, 6000, 2000); f.time(2500);
        f.depth(true, 5101, 3000, 2600); f.time(3100); assertEquals(1, f.events.size());
        f.depth(true, 5101, 0, 3200); f.time(3600); assertEquals(1, f.events.size());
        f.engine.reset(com.bookmap.plugin.rong.signal.ResetReason.REPLAY_SEEK);
        f.depth(true, 5102, 6000, 4000); f.time(5000); assertEquals(1, f.events.size());
    }
}
