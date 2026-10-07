package com.bookmap.plugin.rong.signal;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.signal.CompositionModelsTest.event;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.patterns.*;
import com.google.gson.JsonParser;

class SignalComposerTest {
    @Test void fourMinuteOldBidAndOfferEvidenceCanStillComposeSignals() {
        long start = 1_000_000_000L, later = start + 240_000_000_000L;
        SignalComposer bidFirst = composer();
        bidFirst.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 3000, 5105, start, "bid"));
        assertEquals(1, bidFirst.onPatternEvent(event(PatternEventType.OFFER_HOLD, 60000, 5120, later, "offer")).signals.size());
        SignalComposer offerFirst = composer();
        offerFirst.onPatternEvent(event(PatternEventType.OFFER_BREAKOUT, 60000, 5120, start, "offer"));
        assertEquals(1, offerFirst.onPatternEvent(event(PatternEventType.BID_STEP_UP, 3000, 5105, later, "bid")).signals.size());
    }

    @Test void initiallyDistantTriggerCannotValidateBeforeDriftInvalidation() {
        SignalComposer c = composer(); c.onMarketPrice(5100, 5101, 0);
        assertTrue(c.onPatternEvent(event(PatternEventType.BID_STEP_UP, 6000, 5079, 100, "far")).signals.isEmpty());
        assertTrue(c.candidateStates().isEmpty());
        assertEquals(1, c.onPatternEvent(event(PatternEventType.BID_STEP_UP, 5000, 5080, 200, "local")).signals.size());
    }
    @Test void offerOnlyProducesSeparateWaitingContextsWithoutSignals() {
        SignalComposer c = composer();
        CompositionUpdate bearish = c.onPatternEvent(event(PatternEventType.OFFER_HOLD, 60000, 5120, 100, "bear"));
        assertTrue(bearish.signals.isEmpty());
        DevelopingContext shortContext = bearish.contexts.get(Direction.SHORT);
        assertEquals(PatternMeaning.BID_FAIL, shortContext.waitingFor);
        assertEquals(3000, shortContext.requiredTriggerSize);
        assertEquals(ConfirmationStrength.EXCEPTIONAL, shortContext.strength);
        CompositionUpdate both = c.onPatternEvent(event(PatternEventType.OFFER_BREAKOUT, 10000, 5120, 200, "bull"));
        assertEquals(2, both.contexts.size()); assertTrue(both.signals.isEmpty());
        assertEquals(PatternMeaning.BID_HOLD, both.contexts.get(Direction.LONG).waitingFor);
        assertEquals(5000, both.contexts.get(Direction.LONG).requiredTriggerSize);
        assertThrows(UnsupportedOperationException.class, () -> both.contexts.clear());
    }
    @Test void contextExpiresOnMarketTimeAndResetAndRequiresLocalPrice() {
        SignalComposer c = composer(); c.onPatternEvent(event(PatternEventType.OFFER_HOLD, 60000, 5120, 100, "o"));
        assertEquals(1, c.onMarketTime(300_000_000_100L).contexts.size());
        assertTrue(c.onMarketTime(300_000_000_101L).contexts.isEmpty());
        c = composer(); c.onPatternEvent(event(PatternEventType.OFFER_HOLD, 60000, 5120, 100, "o"));
        assertEquals(1, c.onMarketPrice(5100, 5101, 0).contexts.size());
        assertTrue(c.onMarketPrice(5099, 5100, 0).contexts.isEmpty());
        assertTrue(c.reset(ResetReason.DISABLED, 2).contexts.isEmpty());
    }
    @Test void timestampUpdatesExpireWithoutNewPatternAndKeepInclusiveBoundary() {
        SignalComposer c = composer(); c.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 100, "b"));
        assertTrue(c.onMarketTime(300_000_000_100L).transitions.isEmpty());
        CompositionUpdate expired = c.onMarketTime(300_000_000_101L);
        assertEquals(SignalState.EXPIRED, expired.transitions.get(0).current);
        assertTrue(c.onPatternEvent(event(PatternEventType.OFFER_HOLD, 60000, 5120, 300_000_000_200L, "o")).signals.isEmpty());
    }
    @Test void opposingBidInvalidationKeepsHistoricalSignalUnchanged() {
        SignalComposer c = composer();
        TradingSignal original = c.onPatternEvent(event(PatternEventType.BID_STEP_UP, 5000, 5105, 100, "long")).signals.get(0);
        CompositionUpdate opposite = c.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 200, "short"));
        assertEquals(SignalState.INVALID, opposite.transitions.get(0).current);
        assertEquals(Direction.LONG, original.direction); assertEquals(1, original.revision);
    }
    @Test void priceDriftUsesUsableQuotesAndStrictDistanceBoundary() {
        SignalComposer c = composer(); c.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 100, "b"));
        assertTrue(c.onMarketPrice(0, 0, 0).transitions.isEmpty());
        assertTrue(c.onMarketPrice(5125, 5126, 0).transitions.isEmpty());
        assertEquals(SignalState.INVALID, c.onMarketPrice(5126, 5127, 0).transitions.get(0).current);
    }
    @Test void capacityAndHistoryEvictionInvalidateDependentCandidates() {
        SignalComposerConfig config = SignalComposerConfig.parse(JsonParser.parseString("{\"maxEvents\":1,\"maxCandidates\":2}").getAsJsonObject());
        SignalComposer c = new SignalComposer("TEST", 1, config);
        c.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 100, "b1"));
        CompositionUpdate second = c.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 200, "b2"));
        assertEquals(SignalState.INVALID, second.transitions.get(0).current);
        c.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 300, "b3"));
        assertEquals(2, c.candidateStates().size());
    }
    @Test void resetClearsEpochAndRejectsOldContext() {
        SignalComposer c = composer(); c.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 100, "b"));
        c.reset(ResetReason.REPLAY_SEEK, 2); assertTrue(c.candidateStates().isEmpty());
        assertTrue(c.onPatternEvent(event(PatternEventType.OFFER_HOLD, 60000, 5120, 200, "old")).signals.isEmpty());
        assertTrue(c.onMarketTime(1).transitions.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> c.onMarketTime(0));
    }
    @Test void reappearAndStepOnTheSameWallShareCandidateAndPrimaryTrigger() {
        SignalComposer c = composer();
        TradingSignal first = c.onPatternEvent(event(PatternEventType.BID_STEP_UP, 5000, 5105, 100, "wall")).signals.get(0);
        TradingSignal revision = c.onPatternEvent(event(PatternEventType.BID_REAPPEAR, 6000, 5105, 100, "wall")).signals.get(0);
        assertEquals(1, c.candidateStates().size()); assertEquals(first.id, revision.id);
        assertEquals(2, revision.supportingBidEvidence.size()); assertSame(first.trigger, revision.trigger);
        assertSame(first.firstValidation, revision.firstValidation); assertEquals(100, revision.firstValidation.eventTimeNs);
        assertTrue(c.onPatternEvent(event(PatternEventType.BID_REAPPEAR, 6000, 5105, 100, "wall")).signals.isEmpty());
    }
    @Test void repeatedStepRevisionCannotRefreshOrReplaceTheTrigger() {
        SignalComposer c = composer();
        TradingSignal first = c.onPatternEvent(event(PatternEventType.BID_STEP_UP, 5000, 5105, 100, "wall")).signals.get(0);
        PatternEvent later = PatternEvent.builder("TEST", 1, PatternEventType.BID_STEP_UP, "wall").revision(2)
                .size(6000, PatternEvent.SizeBasis.DISPLAYED_WALL).price(5105, .01).times(100, 200)
                .evidence(PatternEvent.Evidence.builder().attribution(PatternEvent.Attribution.UNKNOWN, PatternEvent.Coverage.USABLE).build()).build();
        TradingSignal revised = c.onPatternEvent(later).signals.get(0);
        assertEquals(first.id, revised.id); assertSame(first.firstValidation, revised.firstValidation);
        assertEquals(5000, revised.trigger.size); assertEquals(100, revised.trigger.eventTimeNs);
        assertEquals(first.createdAtMs, revised.createdAtMs);
        assertTrue(c.onPatternEvent(later).signals.isEmpty());
    }
    @Test void subsequentExceptionalConfirmationLeavesOriginalAcceptanceFrozen() {
        SignalComposer c = composer();
        TradingSignal first = c.onPatternEvent(event(PatternEventType.BID_BREAKDOWN, 5000, 5105, 100, "b")).signals.get(0);
        TradingSignal revised = c.onPatternEvent(event(PatternEventType.OFFER_HOLD, 60000, 5120, 200, "o")).signals.get(0);
        assertEquals(5000, revised.firstValidation.appliedTriggerThreshold);
        assertEquals(ConfirmationStrength.NONE, revised.firstValidation.confirmationStrength);
        assertEquals(ConfirmationStrength.EXCEPTIONAL, revised.latestConfirmationStrength);
        assertSame(first.firstValidation, revised.firstValidation); assertTrue(revised.explanation.contains("after validation"));
    }
    @Test void laterExceptionalOfferPromotesPendingTriggerAtObservationTime() {
        SignalComposer c = composer();
        assertTrue(c.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 100, "b")).signals.isEmpty());
        TradingSignal result = c.onPatternEvent(event(PatternEventType.OFFER_HOLD, 60000, 5120, 200, "o")).signals.get(0);
        assertEquals(200, result.firstValidation.eventTimeNs); assertEquals(100, result.trigger.eventTimeNs);
        assertEquals(3000, result.firstValidation.appliedTriggerThreshold);
    }
    @Test void laterConfirmationEnrichesExistingSignalInsteadOfCreatingAnother() {
        SignalComposer c = composer();
        TradingSignal first = c.onPatternEvent(event(PatternEventType.BID_BREAKDOWN, 5000, 5105, 100, "b")).signals.get(0);
        TradingSignal revised = c.onPatternEvent(event(PatternEventType.OFFER_HOLD, 15000, 5120, 200, "o")).signals.get(0);
        assertEquals(first.id, revised.id); assertEquals(2, revised.revision); assertSame(first.firstValidation, revised.firstValidation);
        assertEquals(ConfirmationStrength.NONE, revised.firstValidation.confirmationStrength);
        assertEquals(ConfirmationStrength.STRONG, revised.latestConfirmationStrength);
        assertEquals(1, revised.subsequentConfirmations.size()); assertEquals(first.createdAtMs, revised.createdAtMs);
    }
    @Test void oneConfirmationCanPromoteMultipleLocalTriggers() {
        SignalComposer c = composer();
        c.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 100, "b1"));
        c.onPatternEvent(event(PatternEventType.BID_BREAKDOWN, 3000, 5106, 150, "b2"));
        assertEquals(2, c.onPatternEvent(event(PatternEventType.OFFER_HOLD, 60000, 5120, 200, "o")).signals.size());
    }
    @Test void insufficientOrTooLateConfirmationCannotPromote() {
        SignalComposer c = composer(); c.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 100, "b"));
        assertTrue(c.onPatternEvent(event(PatternEventType.OFFER_HOLD, 7000, 5120, 200, "o1")).signals.isEmpty());
        assertTrue(c.onPatternEvent(event(PatternEventType.OFFER_HOLD, 60000, 5120, 300_000_000_101L, "o2")).signals.isEmpty());
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
        assertTrue(composer().onPatternEvent(event(PatternEventType.OFFER_HOLD, 60000, 5120, 100, "o")).signals.isEmpty());
        assertTrue(composer().onPatternEvent(event(PatternEventType.OFFER_BREAKOUT, 60000, 5120, 100, "o")).signals.isEmpty());
    }
    @Test void exceptionalPriorOfferPermitsSmallRequiredBidTrigger() {
        SignalComposer c = composer(); c.onPatternEvent(event(PatternEventType.OFFER_HOLD, 60000, 5120, 100, "o"));
        TradingSignal signal = c.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 200, "b")).signals.get(0);
        assertEquals(3000, signal.firstValidation.appliedTriggerThreshold);
        assertEquals(ConfirmationStrength.EXCEPTIONAL, signal.firstValidation.confirmationStrength);
        assertTrue(signal.explanation.contains("Threshold reduced"));
    }
    @Test void smallTriggerRemainsPendingAndHardMinimumCannotBeRescued() {
        SignalComposer c = composer(); c.onPatternEvent(event(PatternEventType.OFFER_HOLD, 7000, 5120, 100, "o"));
        assertTrue(c.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 200, "b")).signals.isEmpty());
        assertTrue(c.candidateStates().containsValue(SignalState.CANDIDATE));
        SignalComposer strong = composer(); strong.onPatternEvent(event(PatternEventType.OFFER_HOLD, 60000, 5120, 100, "o"));
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
