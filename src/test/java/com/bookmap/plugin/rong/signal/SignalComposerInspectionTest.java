package com.bookmap.plugin.rong.signal;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.signal.CompositionModelsTest.event;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.patterns.*;

class SignalComposerInspectionTest {
    @Test void inspectionExplainsPendingPromotionAndResetWithoutChangingState() {
        SignalComposer composer = new SignalComposer("TEST", 1, SignalComposerConfig.defaults(), () -> 1000);
        composer.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 100, "bid"));
        String pending = String.join("\n", composer.inspectionLines());
        assertTrue(pending.contains("SHORT CANDIDATE"));
        assertTrue(pending.contains("bid required ≥ 5000"));
        assertTrue(pending.contains("30.0s left"));
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
        assertTrue(scanning.contains("2026-10-06 09:30:00.000 NY"));
        assertEquals(scanning, pipeline.inspectionText());
        pipeline.onTimestamp(open - 1_000_000_000L, PatternEvent.TimestampProvenance.MARKET);
        assertTrue(pipeline.inspectionText().contains("Last reset: REPLAY_SEEK"));
        assertTrue(pipeline.inspectionText().contains("Waiting for snapshot"));
        pipeline.reset(ResetReason.DISABLED);
        assertTrue(pipeline.inspectionText().contains("Last reset: DISABLED"));
    }
}
