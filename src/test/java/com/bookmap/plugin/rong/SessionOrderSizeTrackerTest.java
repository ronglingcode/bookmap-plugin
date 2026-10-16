package com.bookmap.plugin.rong;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionOrderSizeTrackerTest {
    private static long time(String value) {
        return LocalDateTime.parse(value).atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli() * 1_000_000;
    }
    @Test void removedOrdersBecomeEligibleWhenRangeExpandsIncludingBothBoundaries() {
        OrderBookState book = new OrderBookState(); SessionOrderSizeTracker tracker = new SessionOrderSizeTracker();
        long open = time("2026-10-16T09:30:00");
        tracker.onDepth(99, 10000, open, book, .01);
        tracker.onDepth(99, 0, open + 1, book, .01);
        tracker.onDepth(101, 5000, open + 2, book, .01);
        tracker.onDepth(103, 15000, open + 3, book, .01);
        assertEquals(5000, tracker.largest("2026-10-16", 100, 102));
        assertEquals(10000, tracker.largest("2026-10-16", 99, 102));
        assertEquals(15000, tracker.largest("2026-10-16", 99, 103));
        tracker.onDepth(103, 100, open + 4, book, .01);
        assertEquals(15000, tracker.largest("2026-10-16", 99, 103));
    }
    @Test void premarketClearedOrdersAndAfterHoursDoNotCountAndNextDayResets() {
        OrderBookState book = new OrderBookState(); SessionOrderSizeTracker tracker = new SessionOrderSizeTracker();
        tracker.onDepth(100, 50000, time("2026-10-16T09:29:59"), book, .01);
        book.update(true, 10000, 2000);
        tracker.advance(time("2026-10-16T09:30:00"), book, .01);
        assertEquals(2000, tracker.largest("2026-10-16", 100, 100));
        tracker.onDepth(100, 100000, time("2026-10-16T16:00:00"), book, .01);
        assertEquals(2000, tracker.largest("2026-10-16", 100, 100));
        tracker.advance(time("2026-10-19T09:00:00"), book, .01);
        assertEquals(0, tracker.largest("2026-10-16", 100, 100));
        assertEquals(0, tracker.largest("2026-10-19", 100, 100));
    }
    @Test void serverUsesUnfilteredDepthAndLiveRangeRatherThanOnlyTargetWalls() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 0);
        OrderBookState book = new OrderBookState(); server.registerSymbol("TEST", book, .01);
        long open = time("2026-10-16T09:30:00");
        server.observeSessionDepth("TEST", 9900, 10000, open);
        server.observeSessionDepth("TEST", 9900, 0, open + 1);
        server.observeSessionDepth("TEST", 10000, 2000, open + 2);
        server.updateRegularSessionHighLow("TEST", 100, open + 3);
        assertEquals(.4, server.entrySizeCap("TEST").get("maxRiskMultiple").getAsDouble());
        server.updateRegularSessionHighLow("TEST", 99, open + 4);
        assertEquals(2, server.entrySizeCap("TEST").get("maxRiskMultiple").getAsDouble());
        server.unregisterSymbol("TEST");
        assertEquals(0, server.entrySizeCap("TEST").get("maxRiskMultiple").getAsDouble());
    }
}
