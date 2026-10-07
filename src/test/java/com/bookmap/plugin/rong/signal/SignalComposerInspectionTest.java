package com.bookmap.plugin.rong.signal;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.signal.CompositionModelsTest.event;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.patterns.*;

class SignalComposerInspectionTest {
    @Test void everyGroupIsBoundedAndRevisionsReplaceRatherThanDuplicateAnEvent() {
        SignalComposer composer = new SignalComposer("TEST", 1, SignalComposerConfig.defaults());
        PatternEventType[] types = {PatternEventType.BID_STEP_UP, PatternEventType.BIDS_CANCELLED,
                PatternEventType.OFFER_BREAKOUT, PatternEventType.OFFER_REJECTION};
        for (int i = 0; i < 12; i++) composer.onPatternEvent(event(types[i % 4], 60000, 5105, i + 1, "event" + i));
        composer.onPatternEvent(PatternEvent.builder("TEST", 1, PatternEventType.OFFER_REJECTION, "event11")
                .revision(2).size(65000, PatternEvent.SizeBasis.DISPLAYED_WALL).price(5105, .01).times(12, 13)
                .evidence(PatternEvent.Evidence.builder().attribution(PatternEvent.Attribution.UNKNOWN, PatternEvent.Coverage.USABLE).build()).build());
        for (SignalComposerInspection.Section section : composer.inspectionSections()) {
            assertEquals(2, section.bids.size());
            assertEquals(2, section.offers.size());
            assertTrue(section.bids.get(0).pattern.eventTimeNs > section.bids.get(1).pattern.eventTimeNs);
            assertTrue(section.offers.get(0).pattern.eventTimeNs > section.offers.get(1).pattern.eventTimeNs);
        }
        SignalComposerInspection.Event newest = composer.inspectionSections().get(1).offers.get(0);
        assertEquals(2, newest.pattern.revision);
        assertEquals(65000, newest.pattern.size);
    }

    @Test void sectionsLimitEachSemanticGroupToTwoNewestEventsUntilFiveMinuteRetentionBoundary() {
        SignalComposer composer = new SignalComposer("TEST", 1, SignalComposerConfig.defaults());
        for (int i = 1; i <= 3; i++) composer.onPatternEvent(event(PatternEventType.BID_STEP_UP, 5000, 5105, i, "long" + i));
        composer.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 5000, 5105, 4, "short"));
        composer.onPatternEvent(event(PatternEventType.OFFER_BREAKOUT, 60000, 5105, 5, "longOffer"));
        composer.onPatternEvent(event(PatternEventType.OFFER_REJECTION, 60000, 5105, 6, "shortOffer"));
        composer.onPatternEvent(event(PatternEventType.OFFER_SIZE_INCREASE, 60000, 5105, 7, "unknown"));
        java.util.List<SignalComposerInspection.Section> sections = composer.inspectionSections();
        assertEquals(2, sections.size());
        SignalComposerInspection.Section longs = sections.get(0), shorts = sections.get(1);
        assertEquals(Direction.LONG, longs.direction);
        assertEquals(2, longs.bids.size());
        assertEquals(3, longs.bids.get(0).pattern.eventTimeNs);
        assertEquals(2, longs.bids.get(1).pattern.eventTimeNs);
        assertEquals(PatternEventType.OFFER_BREAKOUT, longs.offers.get(0).pattern.type);
        assertEquals(Direction.SHORT, shorts.direction);
        assertEquals(PatternEventType.BIDS_CANCELLED, shorts.bids.get(0).pattern.type);
        assertEquals(PatternEventType.OFFER_REJECTION, shorts.offers.get(0).pattern.type);
        assertEquals(1, shorts.offers.size());
        assertTrue(longs.offers.get(0).status.isEmpty());
        composer.onMarketPrice(5200, 5202, 0);
        assertEquals("Out of range", composer.inspectionSections().get(0).offers.get(0).status);
        composer.onMarketTime(299_000_000_007L);
        assertEquals("Out of range", composer.inspectionSections().get(0).offers.get(0).status);
        assertEquals(2, composer.inspectionSections().get(0).bids.size());
        composer.onMarketTime(300_000_000_006L);
        assertTrue(composer.inspectionSections().get(0).offers.isEmpty());
        assertEquals(1, composer.inspectionSections().get(1).offers.size());
        composer.onMarketTime(300_000_000_007L);
        assertTrue(composer.inspectionSections().get(1).offers.isEmpty());
        assertEquals(3, longs.bids.get(0).pattern.eventTimeNs); // Previously published snapshot stays intact.
        composer.reset(ResetReason.REPLAY_SEEK, 2);
        assertTrue(composer.inspectionSections().stream().allMatch(s -> s.bids.isEmpty() && s.offers.isEmpty()));
    }

    @Test void inspectionExplainsPendingPromotionAndResetWithoutChangingState() {
        SignalComposer composer = new SignalComposer("TEST", 1, SignalComposerConfig.defaults(), () -> 1000);
        composer.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 100, "bid"));
        String pending = String.join("\n", composer.inspectionLines());
        assertTrue(pending.contains("SHORT CANDIDATE"));
        assertTrue(pending.contains("bid required ≥ 5000"));
        assertTrue(pending.contains("300.0s left"));
        assertEquals(pending, String.join("\n", composer.inspectionLines()));
        composer.onPatternEvent(event(PatternEventType.OFFER_REJECTION, 60000, 5120, 200, "offer"));
        String valid = String.join("\n", composer.inspectionLines());
        assertTrue(valid.contains("SHORT VALID"));
        assertTrue(valid.contains("Validated: EXCEPTIONAL · applied ≥ 3000"));
        assertTrue(valid.contains("Waiting for BID_FAIL ≥ 3000"));
        composer.reset(ResetReason.REPLAY_SEEK, 2);
        assertTrue(String.join("\n", composer.inspectionLines()).contains("Evidence retained: 0"));
        assertTrue(composer.candidateStates().isEmpty());
    }

    @Test void inspectionDistinguishesReadinessSessionAndSeekAndFreezesWhenPolled() {
        SignalCompositionPipeline pipeline = new SignalCompositionPipeline("TEST", .01, SignalComposerConfig.defaults(), update -> {});
        assertTrue(pipeline.inspectionText().contains("Waiting for snapshot"));
        pipeline.markReady();
        assertTrue(pipeline.inspectionText().contains("Waiting for market timestamp"));
        long open = Instant.parse("2026-10-06T13:30:00Z").getEpochSecond() * 1_000_000_000L;
        pipeline.onTimestamp(open - 1_000_000_000L, PatternEvent.TimestampProvenance.MARKET);
        assertTrue(pipeline.inspectionText().contains("Outside regular hours"));
        pipeline.onTimestamp(open, PatternEvent.TimestampProvenance.MARKET);
        String scanning = pipeline.inspectionText();
        assertTrue(scanning.contains("Scanning"));
        assertEquals("", pipeline.inspection().notice);
        assertTrue(scanning.contains("2026-10-06 09:30:00.000 NY"));
        assertEquals(scanning, pipeline.inspectionText());
        pipeline.onTimestamp(open - 1_000_000_000L, PatternEvent.TimestampProvenance.MARKET);
        assertTrue(pipeline.inspectionText().contains("Last reset: REPLAY_SEEK"));
        assertTrue(pipeline.inspectionText().contains("Waiting for snapshot"));
        pipeline.reset(ResetReason.DISABLED);
        assertTrue(pipeline.inspectionText().contains("Last reset: DISABLED"));
    }
}
