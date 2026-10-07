package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.patterns.PatternObservationEngineTest.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.google.gson.JsonParser;
import com.bookmap.plugin.rong.signal.*;

class ObservationCoverageGapTest {
    @Test void tradeBufferOverflowExpiresPendingCandidateAndRequiresFreshReadiness() {
        SignalComposerConfig config = SignalComposerConfig.parse(JsonParser.parseString("{\"maxAttributionTrades\":2}").getAsJsonObject());
        List<CompositionUpdate> updates = new ArrayList<>();
        SignalCompositionPipeline p = new SignalCompositionPipeline("TEST", .01, config, updates::add);
        p.markReady(); p.onTimestamp(BASE, MARKET); p.onBbo(5100, 5121, BASE, MARKET);
        p.onTrade(5090, 1, false, BASE + 100_000_000L, MARKET);
        p.onTrade(5130, 1, true, BASE + 101_000_000L, MARKET);
        p.onDepth(true, 5100, 3000, BASE + 2_000_000_000L, MARKET); p.onTimestamp(BASE + 2_500_000_000L, MARKET);
        p.onDepth(true, 5101, 3000, BASE + 2_600_000_000L, MARKET); p.onTimestamp(BASE + 3_100_000_000L, MARKET);
        assertTrue(updates.stream().allMatch(update -> update.signals.isEmpty()));
        p.onTrade(5100, 100, false, BASE + 3_200_000_000L, MARKET);
        assertFalse(p.usable()); assertEquals(2, p.epoch());
        assertTrue(updates.stream().flatMap(update -> update.transitions.stream())
                .anyMatch(change -> change.current == SignalState.EXPIRED && change.reason.contains("COVERAGE_GAP")));
        p.onTimestamp(BASE + 4_000_000_000L, MARKET); assertFalse(p.usable());
    }
    @Test void normalizedEpisodeOverflowResetsSafelyInsideDefinitionEmission() {
        SignalComposerConfig config = SignalComposerConfig.parse(JsonParser.parseString("{\"maxEvents\":1}").getAsJsonObject());
        List<PatternEvent> events = new ArrayList<>(); List<ResetReason> resets = new ArrayList<>();
        PatternObservationEngine e = new PatternObservationEngine("TEST", .01, config, events::add, (reason, epoch) -> resets.add(reason), diagnostic -> {});
        e.markReady(); e.onTimestamp(BASE, MARKET); e.onBbo(5100, 5121, BASE, MARKET);
        e.onTrade(5090, 1, false, BASE + 100_000_000L, MARKET);
        e.onTrade(5130, 1, true, BASE + 101_000_000L, MARKET);
        e.onDepth(true, 5100, 3000, BASE + 2_000_000_000L, MARKET); e.onTimestamp(BASE + 2_500_000_000L, MARKET);
        e.onTrade(5100, 3000, false, BASE + 2_600_000_000L, MARKET); e.onDepth(true, 5100, 0, BASE + 2_600_000_000L, MARKET);
        e.onTimestamp(BASE + 3_100_000_000L, MARKET); e.onDepth(true, 5101, 3000, BASE + 3_200_000_000L, MARKET);
        e.onTimestamp(BASE + 3_700_000_000L, MARKET);
        assertEquals(List.of(ResetReason.COVERAGE_GAP), resets); assertFalse(e.usable()); assertEquals(2, e.epoch());
        assertEquals(1, events.size());
    }
    @Test void snapshotOverflowDisarmsWithoutAFalseWithdrawal() {
        SignalComposerConfig config = SignalComposerConfig.parse(JsonParser.parseString("{\"maxWallPhases\":1}").getAsJsonObject());
        List<PatternEvent> events = new ArrayList<>(); List<ResetReason> resets = new ArrayList<>();
        PatternObservationEngine e = new PatternObservationEngine("TEST", .01, config, events::add, (reason, epoch) -> resets.add(reason), diagnostic -> {});
        e.onDepth(true, 5100, 3000, BASE, MARKET); e.onDepth(false, 5120, 6000, BASE + 1, MARKET);
        assertEquals(List.of(ResetReason.COVERAGE_GAP), resets); assertTrue(events.isEmpty()); assertFalse(e.usable());
    }
}
