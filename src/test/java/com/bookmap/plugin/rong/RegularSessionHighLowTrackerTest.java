package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

class RegularSessionHighLowTrackerTest {

    private static final long NS_PER_SECOND = 1_000_000_000L;
    private static final ZoneId NEW_YORK_TIME = ZoneId.of("America/New_York");

    @Test void chartClockExcludesFutureAndUnfinishedMinuteExtremesUntilTheyAreKnown() {
        RegularSessionHighLowTracker tracker = new RegularSessionHighLowTracker();
        long open = timestampNs(2026, 10, 16, 9, 30);
        tracker.onTimestamp(open + 5 * NS_PER_SECOND);
        tracker.onTrade(369.24, open + 5 * NS_PER_SECOND);
        tracker.onMinuteBars("2026-10-16", List.of(
                bar(2026, 10, 16, 9, 30, 369.15, 370, 368),
                bar(2026, 10, 16, 10, 0, 369, 370.58, 356.33)));
        assertEquals(369.24, tracker.snapshot().getHighOfDay());
        assertEquals(369.24, tracker.snapshot().getLowOfDay());
        assertEquals(369.15, tracker.snapshot().getOpenPrice());
        tracker.onTimestamp(open + 60 * NS_PER_SECOND);
        assertEquals(370, tracker.snapshot().getHighOfDay());
        assertEquals(368, tracker.snapshot().getLowOfDay());
        tracker.onTimestamp(timestampNs(2026, 10, 16, 10, 1));
        assertEquals(370.58, tracker.snapshot().getHighOfDay());
        assertEquals(356.33, tracker.snapshot().getLowOfDay());
        tracker.onTimestamp(open + 5 * NS_PER_SECOND);
        assertNull(tracker.snapshot());
        tracker.onTrade(369.24, open + 5 * NS_PER_SECOND);
        assertEquals(369.24, tracker.snapshot().getLowOfDay());
    }

    @Test void chartClockAlsoBoundsHistoryLoadedBeforeFirstTimestamp() {
        RegularSessionHighLowTracker tracker = new RegularSessionHighLowTracker();
        tracker.onMinuteBars("2026-10-16", List.of(bar(2026, 10, 16, 9, 30, 369, 371, 356)));
        tracker.onTimestamp(timestampNs(2026, 10, 16, 9, 30));
        assertNull(tracker.snapshot());
        tracker.onTrade(369, timestampNs(2026, 10, 16, 9, 30));
        assertEquals(369, tracker.snapshot().getLowOfDay());
    }

    @Test void liveLateAttachmentRepairsEarlierRangeFromCompletedHistoryAndKeepsIncomingTrades() {
        RegularSessionHighLowTracker tracker = new RegularSessionHighLowTracker();
        long current = timestampNs(2026, 10, 16, 11, 0) + 5 * NS_PER_SECOND;
        tracker.onTimestamp(current); tracker.onTrade(107, current);
        tracker.onMinuteBars("2026-10-16", List.of(
                bar(2026, 10, 16, 9, 30, 100, 104, 98),
                bar(2026, 10, 16, 10, 59, 102, 106, 99),
                bar(2026, 10, 16, 11, 0, 103, 120, 70)));
        assertEquals(107, tracker.snapshot().getHighOfDay());
        assertEquals(98, tracker.snapshot().getLowOfDay());
        assertEquals(100, tracker.snapshot().getOpenPrice());
    }

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
        assertEquals(102.75, snapshot.getHighOfDay());
        assertEquals(99.50, snapshot.getLowOfDay());
        assertEquals("real", snapshot.toJson().get("priceUnit").getAsString());
    }

    @Test
    void resetsWhenNewYorkSessionDateChanges() {
        RegularSessionHighLowTracker tracker = new RegularSessionHighLowTracker();
        tracker.onTrade(101.25, timestampNs(2026, 7, 15, 9, 30));

        tracker.onTrade(200.00, timestampNs(2026, 7, 16, 8, 0));

        assertNull(tracker.snapshot());

        tracker.onTrade(210.00, timestampNs(2026, 7, 16, 9, 30));

        RegularSessionHighLowTracker.Snapshot snapshot = tracker.snapshot();
        assertEquals(210.00, snapshot.getHighOfDay());
        assertEquals(210.00, snapshot.getLowOfDay());
    }

    @Test
    void minuteHistoryRepairsLateStartAndSuppliesOpeningPrice() {
        RegularSessionHighLowTracker tracker = new RegularSessionHighLowTracker();
        tracker.onTrade(237.55, timestampNs(2026, 10, 2, 9, 52));
        tracker.onTrade(235.09, timestampNs(2026, 10, 2, 9, 53));

        tracker.onMinuteBars("2026-10-02", List.of(
                bar(2026, 10, 2, 9, 29, 230, 240, 220),
                bar(2026, 10, 2, 9, 30, 234.50, 236, 233.75),
                bar(2026, 10, 2, 9, 31, 235, 236.50, 232.25),
                bar(2026, 10, 3, 9, 30, 10, 300, 1)));

        RegularSessionHighLowTracker.Snapshot snapshot = tracker.snapshot();
        assertEquals(234.50, snapshot.getOpenPrice());
        assertEquals(237.55, snapshot.getHighOfDay());
        assertEquals(232.25, snapshot.getLowOfDay());
        assertEquals(234.50, snapshot.toJson().get("openPrice").getAsDouble());
        assertEquals("2026-10-02", snapshot.toJson().get("sessionDate").getAsString());
        assertEquals("bookmap+massive", snapshot.toJson().get("source").getAsString());
    }

    @Test
    void requiresActualOpeningMinuteAndIgnoresAfterHours() {
        RegularSessionHighLowTracker tracker = new RegularSessionHighLowTracker();
        tracker.onMinuteBars("2026-07-15", List.of(
                bar(2026, 7, 15, 9, 31, 101, 102, 100),
                bar(2026, 7, 15, 16, 0, 80, 110, 70)));
        tracker.onTrade(150, timestampNs(2026, 7, 15, 16, 1));

        RegularSessionHighLowTracker.Snapshot snapshot = tracker.snapshot();
        assertEquals(102, snapshot.getHighOfDay());
        assertEquals(100, snapshot.getLowOfDay());
        assertFalse(snapshot.toJson().has("openPrice"));
    }

    @Test
    void oldHistoryAndTradesCannotRewindNewSession() {
        RegularSessionHighLowTracker tracker = new RegularSessionHighLowTracker();
        tracker.onTrade(210, timestampNs(2026, 7, 16, 9, 35));
        tracker.onMinuteBars("2026-07-15", List.of(bar(2026, 7, 15, 9, 30, 100, 101, 99)));
        tracker.onTrade(90, timestampNs(2026, 7, 15, 9, 45));

        assertEquals(210, tracker.snapshot().getHighOfDay());
        assertEquals(210, tracker.snapshot().getLowOfDay());
    }

    @Test
    void wallLabelShowsOpenFromMinuteHistory() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 97);
        server.updateRegularSessionMinuteBars("TEST", "2026-10-02",
                List.of(bar(2026, 10, 2, 9, 30, 234.50, 236, 233.75)));

        assertEquals("HOD/LOD: 236.00/233.75 | Open: 234.50",
                server.describeRegularSessionHighLow("TEST"));
    }

    @Test
    void displayAndOrderPayloadReadMaintainedMarketStateWhenAvailable() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 97);
        server.updateRegularSessionHighLow("TEST", 235.09, timestampNs(2026, 10, 2, 9, 52));
        server.setSessionLevelsProvider(symbol -> {
            com.google.gson.JsonObject market = new com.google.gson.JsonObject();
            market.addProperty("sessionDate", "2026-10-02");
            market.addProperty("openPrice", 234.5);
            market.addProperty("highOfDay", 237.55);
            market.addProperty("lowOfDay", 232.25);
            return market;
        });

        assertEquals("HOD/LOD: 237.55/232.25 | Open: 234.50",
                server.describeRegularSessionHighLow("TEST"));
        com.google.gson.JsonObject action = new com.google.gson.JsonObject();
        server.appendRegularSessionHighLow("TEST", action);
        assertEquals(232.25, action.getAsJsonObject("bookmapDayHighLow").get("lowOfDay").getAsDouble());
    }

    private static Candle bar(int year, int month, int day, int hour, int minute,
                              double open, double high, double low) {
        return new Candle("TEST", timestampNs(year, month, day, hour, minute) / 1_000_000,
                open, high, low, open, 100, open);
    }

    private static long timestampNs(int year, int month, int day, int hour, int minute) {
        ZonedDateTime dateTime = ZonedDateTime.of(
                LocalDate.of(year, month, day),
                LocalTime.of(hour, minute),
                NEW_YORK_TIME);
        return dateTime.toEpochSecond() * NS_PER_SECOND + dateTime.getNano();
    }
}
