package com.bookmap.plugin.rong.signal;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.signal.CompositionModelsTest.event;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.patterns.*;

class SignalComposerTest {
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
