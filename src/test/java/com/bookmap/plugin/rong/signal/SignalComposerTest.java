package com.bookmap.plugin.rong.signal;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.signal.CompositionModelsTest.event;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.patterns.*;

class SignalComposerTest {
    @Test void laterExceptionalOfferPromotesPendingTriggerAtObservationTime() {
        SignalComposer c = composer();
        assertTrue(c.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 100, "b")).signals.isEmpty());
        TradingSignal result = c.onPatternEvent(event(PatternEventType.OFFER_REJECTION, 60000, 5120, 200, "o")).signals.get(0);
        assertEquals(200, result.firstValidation.eventTimeNs); assertEquals(100, result.trigger.eventTimeNs);
        assertEquals(3000, result.firstValidation.appliedTriggerThreshold);
    }
    @Test void laterConfirmationEnrichesExistingSignalInsteadOfCreatingAnother() {
        SignalComposer c = composer();
        TradingSignal first = c.onPatternEvent(event(PatternEventType.BID_BREAKDOWN, 5000, 5105, 100, "b")).signals.get(0);
        TradingSignal revised = c.onPatternEvent(event(PatternEventType.OFFER_REJECTION, 15000, 5120, 200, "o")).signals.get(0);
        assertEquals(first.id, revised.id); assertEquals(2, revised.revision); assertSame(first.firstValidation, revised.firstValidation);
        assertEquals(ConfirmationStrength.NONE, revised.firstValidation.confirmationStrength);
        assertEquals(ConfirmationStrength.STRONG, revised.latestConfirmationStrength);
        assertEquals(1, revised.subsequentConfirmations.size()); assertEquals(first.createdAtMs, revised.createdAtMs);
    }
    @Test void oneConfirmationCanPromoteMultipleLocalTriggers() {
        SignalComposer c = composer();
        c.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 100, "b1"));
        c.onPatternEvent(event(PatternEventType.BID_BREAKDOWN, 3000, 5106, 150, "b2"));
        assertEquals(2, c.onPatternEvent(event(PatternEventType.OFFER_REJECTION, 60000, 5120, 200, "o")).signals.size());
    }
    @Test void insufficientOrTooLateConfirmationCannotPromote() {
        SignalComposer c = composer(); c.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 100, "b"));
        assertTrue(c.onPatternEvent(event(PatternEventType.OFFER_REJECTION, 7000, 5120, 200, "o1")).signals.isEmpty());
        assertTrue(c.onPatternEvent(event(PatternEventType.OFFER_REJECTION, 60000, 5120, 30_000_000_101L, "o2")).signals.isEmpty());
    }
    private SignalComposer composer() { return new SignalComposer("TEST", 1, SignalComposerConfig.defaults(), () -> 1000); }
    @Test void normalBidHoldAndFailureNeedNoConfirmation() {
        TradingSignal longSignal = composer().onPatternEvent(event(PatternEventType.BID_STEP_UP, 5000, 5105, 100, "b")).signals.get(0);
        assertEquals(Direction.LONG, longSignal.direction); assertEquals(ConfirmationStrength.NONE, longSignal.firstValidation.confirmationStrength);
        assertEquals(5000, longSignal.firstValidation.appliedTriggerThreshold);
        TradingSignal shortSignal = composer().onPatternEvent(event(PatternEventType.BID_BREAKDOWN, 6000, 5105, 100, "b")).signals.get(0);
        assertEquals(Direction.SHORT, shortSignal.direction);
    }
    @Test void offerOnlyEvidenceNeverCreatesATradingSignal() {
        assertTrue(composer().onPatternEvent(event(PatternEventType.OFFER_REJECTION, 60000, 5120, 100, "o")).signals.isEmpty());
        assertTrue(composer().onPatternEvent(event(PatternEventType.OFFER_BREAKOUT, 60000, 5120, 100, "o")).signals.isEmpty());
    }
    @Test void exceptionalPriorOfferPermitsSmallRequiredBidTrigger() {
        SignalComposer c = composer(); c.onPatternEvent(event(PatternEventType.OFFER_REJECTION, 60000, 5120, 100, "o"));
        TradingSignal signal = c.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 200, "b")).signals.get(0);
        assertEquals(3000, signal.firstValidation.appliedTriggerThreshold);
        assertEquals(ConfirmationStrength.EXCEPTIONAL, signal.firstValidation.confirmationStrength);
        assertTrue(signal.explanation.contains("Threshold reduced"));
    }
    @Test void smallTriggerRemainsPendingAndHardMinimumCannotBeRescued() {
        SignalComposer c = composer(); c.onPatternEvent(event(PatternEventType.OFFER_REJECTION, 7000, 5120, 100, "o"));
        assertTrue(c.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 200, "b")).signals.isEmpty());
        assertTrue(c.candidateStates().containsValue(SignalState.CANDIDATE));
        SignalComposer strong = composer(); strong.onPatternEvent(event(PatternEventType.OFFER_REJECTION, 60000, 5120, 100, "o"));
        assertTrue(strong.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 2999, 5105, 200, "b")).signals.isEmpty());
        assertTrue(strong.candidateStates().isEmpty());
    }
    @Test void rejectsWarmupAndForeignContext() {
        PatternEvent warmup = PatternEvent.builder("TEST", 1, PatternEventType.BID_STEP_UP, "w")
                .size(5000, PatternEvent.SizeBasis.DISPLAYED_WALL).price(5105, .01).times(100, 100).build();
        assertTrue(composer().onPatternEvent(warmup).signals.isEmpty());
        assertTrue(new SignalComposer("OTHER", 1, SignalComposerConfig.defaults()).onPatternEvent(event(PatternEventType.BID_STEP_UP, 5000, 5105, 100, "b")).signals.isEmpty());
    }
}
