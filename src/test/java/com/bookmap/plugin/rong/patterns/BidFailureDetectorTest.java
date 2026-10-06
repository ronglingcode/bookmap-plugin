package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import static com.bookmap.plugin.rong.patterns.PatternObservationEngineTest.*;

class BidFailureDetectorTest {
    @Test void consumptionRequiresActualBelowPrintAndRetainsPrintDuringClearDecision() {
        Fixture f = qualified(); f.trade(5100, 2100, false, 2900); f.depth(true, 5100, 0, 3000); f.time(3500);
        assertTrue(f.events.isEmpty()); f.trade(5100, 1, false, 3600); assertTrue(f.events.isEmpty());
        f.trade(5099, 1, false, 3700);
        PatternEvent event = f.events.get(0); assertEquals(PatternEventType.BID_BREAKDOWN, event.type);
        assertEquals(PatternMeaning.BID_FAIL, event.meaning); assertEquals(3000, event.size);
        assertEquals(BASE + 3_700_000_000L, event.eventTimeNs);
        Fixture early = qualified(); early.trade(5100, 3000, false, 2900); early.depth(true, 5100, 0, 3000);
        early.trade(5099, 1, false, 3100); assertTrue(early.events.isEmpty()); early.time(3500);
        assertEquals(BASE + 3_100_000_000L, early.events.get(0).eventTimeNs);
        assertEquals(BASE + 3_500_000_000L, early.events.get(0).observedAtNs);
    }
    @Test void insufficientConsumptionWrongDirectionExpiryAndResetCannotBreakDown() {
        Fixture small = qualified(); small.trade(5100, 2000, false, 2900); small.depth(true, 5100, 0, 3000);
        small.time(3500); small.trade(5099, 1, false, 3600); assertTrue(small.events.isEmpty());
        Fixture wrong = qualified(); wrong.trade(5100, 3000, false, 2900); wrong.depth(true, 5100, 0, 3000);
        wrong.time(3500); wrong.trade(5101, 1, true, 3600); assertTrue(wrong.events.isEmpty());
        wrong.trade(5099, 1, false, 6001); assertTrue(wrong.events.isEmpty());
        Fixture reset = qualified(); reset.trade(5100, 3000, false, 2900); reset.depth(true, 5100, 0, 3000); reset.time(3500);
        reset.engine.reset(com.bookmap.plugin.rong.signal.ResetReason.DISABLED); reset.engine.markReady();
        reset.trade(5099, 1, false, 3600); assertTrue(reset.events.isEmpty());
    }
    @Test void persistentThreeThousandBidClearEmitsInferredWithdrawalAtDecisionTime() {
        Fixture f = new Fixture(); f.depth(true, 5100, 3000, 2000); f.time(2500);
        f.depth(true, 5100, 0, 3000); f.time(3500);
        PatternEvent e = f.events.get(0); assertEquals(PatternEventType.BIDS_CANCELLED, e.type);
        assertEquals(3000, e.size); assertEquals(PatternEvent.SizeBasis.DISPLAYED_REMOVED, e.sizeBasis);
        assertEquals(BASE + 3_000_000_000L, e.eventTimeNs); assertEquals(BASE + 3_500_000_000L, e.observedAtNs);
        assertEquals(PatternEvent.Attribution.INFERRED_WITHDRAWAL, e.evidence.attribution);
    }
    @Test void flashReloadUnknownTradesConsumptionAndProbableMoveNeverCancel() {
        Fixture flash = new Fixture(); flash.depth(true, 5100, 3000, 2000); flash.depth(true, 5100, 0, 2100); flash.time(3000);
        assertTrue(flash.events.isEmpty());
        Fixture reload = qualified(); reload.depth(true, 5100, 0, 3000); reload.depth(true, 5100, 3000, 3100); reload.time(3500);
        assertTrue(reload.events.isEmpty());
        Fixture unknown = qualified(); unknown.trade(5100, 100, null, 2900); unknown.depth(true, 5100, 0, 3000); unknown.time(3500);
        assertTrue(unknown.events.isEmpty());
        Fixture consumed = qualified(); consumed.trade(5100, 3000, false, 2900); consumed.depth(true, 5100, 0, 3000); consumed.time(3500);
        assertTrue(consumed.events.isEmpty());
        Fixture move = qualified(); move.depth(true, 5100, 0, 3000); move.depth(true, 5101, 3000, 3200); move.time(3500);
        assertTrue(move.events.isEmpty());
    }
    private Fixture qualified() { Fixture f = new Fixture(); f.depth(true, 5100, 3000, 2000); f.time(2500); return f; }
}
