package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

class RegularSessionHighLowTrackerTest {

    private static final long NS_PER_SECOND = 1_000_000_000L;
    private static final ZoneId NEW_YORK_TIME = ZoneId.of("America/New_York");

    @Test
    void ignoresTradesBeforeRegularMarketOpen() {
        RegularSessionHighLowTracker tracker = new RegularSessionHighLowTracker();

        tracker.onTrade(100.00, timestampNs(2026, 7, 15, 9, 29));

        assertNull(tracker.snapshot());
    }

    @Test
    void tracksHighAndLowFromNewYorkMarketOpen() {
        RegularSessionHighLowTracker tracker = new RegularSessionHighLowTracker();

        tracker.onTrade(101.25, timestampNs(2026, 7, 15, 9, 30));
        tracker.onTrade(99.50, timestampNs(2026, 7, 15, 10, 0));
        tracker.onTrade(102.75, timestampNs(2026, 7, 15, 10, 30));

        RegularSessionHighLowTracker.Snapshot snapshot = tracker.snapshot();
        assertEquals(102.75, snapshot.getHigh());
        assertEquals(99.50, snapshot.getLow());
    }

    @Test
    void resetsWhenNewYorkSessionDateChanges() {
        RegularSessionHighLowTracker tracker = new RegularSessionHighLowTracker();
        tracker.onTrade(101.25, timestampNs(2026, 7, 15, 9, 30));

        tracker.onTrade(200.00, timestampNs(2026, 7, 16, 8, 0));

        assertNull(tracker.snapshot());

        tracker.onTrade(210.00, timestampNs(2026, 7, 16, 9, 30));

        RegularSessionHighLowTracker.Snapshot snapshot = tracker.snapshot();
        assertEquals(210.00, snapshot.getHigh());
        assertEquals(210.00, snapshot.getLow());
    }

    private static long timestampNs(int year, int month, int day, int hour, int minute) {
        ZonedDateTime dateTime = ZonedDateTime.of(
                LocalDate.of(year, month, day),
                LocalTime.of(hour, minute),
                NEW_YORK_TIME);
        return dateTime.toEpochSecond() * NS_PER_SECOND + dateTime.getNano();
    }
}
