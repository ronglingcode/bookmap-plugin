package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatternSignalArtifactsTest {

    @TempDir
    Path tempDir;

    @Test
    void storeUpdatesEpisodeInsteadOfDuplicatingAndExpiresAtThirtySeconds() {
        PatternSignalStore store = new PatternSignalStore();
        BookmapPatternSignal first = signal("episode", 60, 1_000);
        BookmapPatternSignal update = signal("episode", 75, 2_000);
        store.addOrUpdate(first);
        store.addOrUpdate(update);

        List<BookmapPatternSignal> active = store.getRecentSignals("TEST", 2_001);
        assertEquals(1, active.size());
        assertEquals(75, active.get(0).getScore());
        assertTrue(store.getRecentSignals(
                "TEST", 2_000 + PatternSignalStore.DISPLAY_TTL_MS + 1).isEmpty());
    }

    @Test
    void loggerWritesExplainableJsonLines() throws Exception {
        Path file = tempDir.resolve("pattern-signals.jsonl");
        try (PatternSignalLogger logger = new PatternSignalLogger(file)) {
            logger.append(signal("one", 72, 1_000));
            logger.append(signal("two", 84, 2_000));
        }
        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"type\":\"bookmap_pattern_signal\""));
        assertTrue(lines.get(0).contains("\"scoreContributions\""));
    }

    @Test
    void painterBadgeUsesRequestedHeatmapHeadlineAndReasons() {
        BookmapPatternSignal signal = signal("episode", 82, 1_000);
        assertEquals("SHORT · Offer V-Shape · 82", PatternSignalPainter.headline(signal));
        assertTrue(PatternSignalPainter.reasonLine(signal).contains("fast extreme break"));
        assertTrue(PatternSignalPainter.reasonLine(signal).contains("nearby large bid"));
    }

    private static BookmapPatternSignal signal(String episode, int score, long createdAtMs) {
        return new BookmapPatternSignal(
                episode,
                "TEST",
                PatternType.OFFER_V_SHAPE_REJECTION,
                9_900,
                99.0,
                10_000,
                100,
                score,
                Arrays.asList(
                        new ScoreContribution("vshape.extreme_15s", 15, "fast extreme break"),
                        new ScoreContribution("liquidity.opposing_wall_2x", -20, "nearby large bid")),
                createdAtMs * 1_000_000L,
                createdAtMs);
    }
}
