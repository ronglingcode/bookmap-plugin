package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

class VwapTrackerTest {

    private static final long NS_PER_SECOND = 1_000_000_000L;

    @Test
    void seedMustUseNineOhFiveNewYorkHandoff() {
        LocalDate sessionDate = LocalDate.of(2026, 7, 28);

        new VwapSeedDefinition(
                "AAPL",
                sessionDate,
                timestampMs(sessionDate, 9, 5),
                1_000,
                100_000,
                timestampMs(sessionDate, 9, 6));

        assertThrows(IllegalArgumentException.class, () -> new VwapSeedDefinition(
                "AAPL",
                sessionDate,
                timestampMs(sessionDate, 9, 0),
                1_000,
                100_000,
                timestampMs(sessionDate, 9, 6)));
    }

    @Test
    void seedIncludesOnlyBufferedBookmapTradesAtOrAfterHandoff() {
        LocalDate sessionDate = LocalDate.of(2026, 7, 28);
        VwapTracker tracker = new VwapTracker("AAPL");
        tracker.onTrade(99.00, 10, timestampNs(sessionDate, 9, 4));
        tracker.onTrade(101.00, 20, timestampNs(sessionDate, 9, 5));
        tracker.onTrade(102.00, 30, timestampNs(sessionDate, 9, 6));

        assertTrue(tracker.applySeed(seed("AAPL", sessionDate, 1_000, 100_000)));

        VwapTracker.Snapshot snapshot = tracker.snapshot();
        assertEquals(1_050.0, snapshot.getCumulativeVolume(), 0.00001);
        assertEquals(105_080.0, snapshot.getCumulativeNotional(), 0.00001);
        assertEquals(105_080.0 / 1_050.0, snapshot.getVwap(), 0.00001);

        List<VwapTracker.VwapPoint> points = tracker.drainPendingIndicatorPoints();
        assertEquals(3, points.size());
        assertEquals(100.0, points.get(0).getValue(), 0.00001);
        assertEquals(102_020.0 / 1_020.0, points.get(1).getValue(), 0.00001);
        assertEquals(105_080.0 / 1_050.0, points.get(2).getValue(), 0.00001);
    }

    @Test
    void continuesWithLiveTradesAndDoesNotApplyDuplicateSeed() {
        LocalDate sessionDate = LocalDate.of(2026, 7, 28);
        VwapTracker tracker = new VwapTracker("AAPL");
        VwapSeedDefinition seed = seed("AAPL", sessionDate, 1_000, 100_000);
        assertTrue(tracker.applySeed(seed));

        VwapTracker.VwapPoint point =
                tracker.onTrade(110.00, 100, timestampNs(sessionDate, 9, 10));
        assertEquals(111_000.0 / 1_100.0, point.getValue(), 0.00001);
        assertFalse(tracker.applySeed(seed));
        assertEquals(1_100.0, tracker.snapshot().getCumulativeVolume(), 0.00001);
        assertEquals(111_000.0, tracker.snapshot().getCumulativeNotional(), 0.00001);
    }

    @Test
    void newerSessionSeedResetsTheAccumulator() {
        LocalDate firstDate = LocalDate.of(2026, 7, 28);
        LocalDate secondDate = firstDate.plusDays(1);
        VwapTracker tracker = new VwapTracker("AAPL");
        tracker.applySeed(seed("AAPL", firstDate, 1_000, 100_000));
        tracker.onTrade(110.00, 100, timestampNs(firstDate, 9, 10));
        tracker.onTrade(205.00, 50, timestampNs(secondDate, 9, 5));

        assertTrue(tracker.applySeed(seed("AAPL", secondDate, 2_000, 400_000)));

        VwapTracker.Snapshot snapshot = tracker.snapshot();
        assertEquals(secondDate, snapshot.getSessionDate());
        assertEquals(2_050.0, snapshot.getCumulativeVolume(), 0.00001);
        assertEquals(410_250.0, snapshot.getCumulativeNotional(), 0.00001);
    }

    private static VwapSeedDefinition seed(
            String symbol,
            LocalDate sessionDate,
            double volume,
            double notional) {
        return new VwapSeedDefinition(
                symbol,
                sessionDate,
                timestampMs(sessionDate, 9, 5),
                volume,
                notional,
                timestampMs(sessionDate, 9, 6));
    }

    private static long timestampNs(LocalDate date, int hour, int minute) {
        return timestampMs(date, hour, minute) * 1_000_000L;
    }

    private static long timestampMs(LocalDate date, int hour, int minute) {
        ZonedDateTime dateTime = ZonedDateTime.of(
                date,
                LocalTime.of(hour, minute),
                VwapSeedDefinition.NEW_YORK_TIME);
        return dateTime.toEpochSecond() * 1_000L
                + dateTime.getNano() / (NS_PER_SECOND / 1_000L);
    }
}
