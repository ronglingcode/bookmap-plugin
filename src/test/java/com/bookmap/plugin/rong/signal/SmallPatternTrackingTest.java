package com.bookmap.plugin.rong.signal;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.signal.CompositionModelsTest.event;
import com.bookmap.plugin.rong.patterns.*;
import org.junit.jupiter.api.Test;

class SmallPatternTrackingTest {
    @Test void leastSignificantOffersCannotRelaxRequirementsEvenWithSmallerConfirmationBaseline() {
        SignalComposerConfig config = SignalComposerConfig.parse(com.google.gson.JsonParser.parseString(
                "{\"normalConfirmationSize\":100}").getAsJsonObject());
        assertTrue(config.valid, config.error);
        ConfirmationStrength strength = new ConfirmationStrengthClassifier(config).classify(2999);
        assertEquals(ConfirmationStrength.BELOW_NORMAL, strength);
        assertEquals(5000, new TriggerRequirementPolicy(config).requiredSize(strength));
    }
    @Test void smallBidIsNotRetainedEvenWithExceptionalConfirmation() {
        SignalComposer c = new SignalComposer("TEST", 1, SignalComposerConfig.defaults());
        c.onPatternEvent(event(PatternEventType.OFFER_BREAKOUT, 60000, 5120, 100, "offer"));
        for (long size : new long[] {1000, 2999}) {
            assertTrue(c.onPatternEvent(event(PatternEventType.BID_STEP_UP, size, 5105, 200 + size, "bid" + size)).signals.isEmpty());
        }
        assertTrue(c.candidateStates().isEmpty());
        assertFalse(String.join("\n", c.inspectionLines()).contains("Least significant:"));
    }
    @Test void revisionCrossingThreeThousandPromotesSameEpisodeAndUpdatesCategory() {
        SignalComposer c = new SignalComposer("TEST", 1, SignalComposerConfig.defaults());
        c.onPatternEvent(event(PatternEventType.OFFER_BREAKOUT, 60000, 5120, 100, "offer"));
        c.onPatternEvent(event(PatternEventType.BID_STEP_UP, 1000, 5105, 200, "bid"));
        PatternEvent revision = PatternEvent.builder("TEST", 1, PatternEventType.BID_STEP_UP, "bid").revision(1)
                .size(3000, PatternEvent.SizeBasis.DISPLAYED_WALL).price(5105, .01).times(200, 300)
                .evidence(PatternEvent.Evidence.builder().attribution(PatternEvent.Attribution.UNKNOWN, PatternEvent.Coverage.USABLE).build()).build();
        TradingSignal signal = c.onPatternEvent(revision).signals.get(0);
        assertEquals(PatternSizeCategory.BELOW_NORMAL, signal.trigger.sizeCategory);
        assertEquals(3000, signal.firstValidation.appliedTriggerThreshold);
        String inspection = String.join("\n", c.inspectionLines());
        assertFalse(inspection.contains("Least significant:"));
        assertTrue(inspection.contains("Below normal: 1 patterns"));
    }
    @Test void smallOfferNeverRelaxesThresholdAndSmallOpposingBidDoesNotInvalidate() {
        SignalComposer c = new SignalComposer("TEST", 1, SignalComposerConfig.defaults());
        c.onPatternEvent(event(PatternEventType.OFFER_BREAKOUT, 2000, 5120, 100, "offer"));
        assertTrue(c.onPatternEvent(event(PatternEventType.BID_STEP_UP, 3000, 5105, 200, "bid")).signals.isEmpty());
        CompositionUpdate update = c.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 2000, 5105, 300, "opposing"));
        assertTrue(update.transitions.isEmpty());
        assertTrue(c.candidateStates().containsValue(SignalState.CANDIDATE));
        assertEquals(5000, update.contexts.get(Direction.LONG).requiredTriggerSize);
        assertTrue(String.join("\n", c.inspectionLines()).contains("Least significant"));
    }
}
