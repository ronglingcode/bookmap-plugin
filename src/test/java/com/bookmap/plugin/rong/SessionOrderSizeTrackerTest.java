package com.bookmap.plugin.rong;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionOrderSizeTrackerTest {
    private static long time(String value) {
        return LocalDateTime.parse(value).atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli() * 1_000_000;
    }
    @Test void futureRuntimeRangeCannotQualifyAnOrderOutsideFiveSecondChartRange() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 0);
        OrderBookState book = new OrderBookState(); server.registerSymbol("TEST", book, .01);
        server.setSessionLevelsProvider(symbol -> com.google.gson.JsonParser.parseString(
                "{\"sessionDate\":\"2026-10-16\",\"highOfDay\":370.58,\"lowOfDay\":356.33,\"openPrice\":369.15}").getAsJsonObject());
        long open = time("2026-10-16T09:30:00");
        server.observeSessionTime("TEST", open);
        server.updateRegularSessionHighLow("TEST", 369.15, open);
        server.observeSessionDepth("TEST", false, 37050, 7600, open);
        server.observeSessionDepth("TEST", true, 36800, 5000, open);
        server.observeSessionDepth("TEST", false, 36930, 2000, open);
        server.observeSessionTime("TEST", open + 5_000_000_000L);
        server.updateRegularSessionHighLow("TEST", 369.30, open + 5_000_000_000L);
        assertEquals(2000, server.entrySizeCap("TEST").get("largestOrderSize").getAsInt());
        assertEquals(369.15, server.getRegularSessionHighLow("TEST").getLowOfDay());
        assertEquals(369.30, server.getRegularSessionHighLow("TEST").getHighOfDay());
        assertEquals(369.30, server.entrySizeCap("TEST").get("largestOrderPrice").getAsDouble());
        assertEquals("offer", server.entrySizeCap("TEST").get("largestOrderSide").getAsString());
        assertEquals(open / 1_000_000L, server.entrySizeCap("TEST").get("largestOrderObservedAt").getAsLong());
        server.updateRegularSessionHighLow("TEST", 370.50, open + 5_000_000_000L);
        assertEquals(7600, server.entrySizeCap("TEST").get("largestOrderSize").getAsInt());
    }

    @Test void replayRewindDropsFutureMaximaAndDoesNotSeedFromStaleSharedDepth() {
        OrderBookState book = new OrderBookState(); SessionOrderSizeTracker tracker = new SessionOrderSizeTracker();
        long open = time("2026-10-16T09:30:00");
        tracker.onDepth(false, 369.30, 7600, open + 60_000_000_000L, book, .01);
        book.update(false, 36930, 7600);
        tracker.advance(open + 5_000_000_000L, book, .01);
        assertEquals(0, tracker.largest("2026-10-16", 369, 370));
        tracker.onDepth(true, 369.15, 2000, open + 5_000_000_000L, book, .01);
        assertEquals(2000, tracker.largest("2026-10-16", 369, 370));
        // A seek back into premarket must still count fresh resting liquidity at the next open.
        tracker.advance(open - 1_000_000_000L, book, .01);
        tracker.onDepth(false, 369.30, 3000, open - 1_000_000_000L, book, .01);
        tracker.advance(open, book, .01);
        assertEquals(3000, tracker.largest("2026-10-16", 369, 370));
    }
    @Test void invalidReplayDepthPricesCannotCrashCallbacksOrOpeningSnapshot() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 0);
        OrderBookState book = new OrderBookState(); server.registerSymbol("TEST", book, .01);
        long open = time("2026-10-16T09:30:00");
        for (long timestamp : new long[]{open - 1, open}) {
            for (int price : new int[]{0, -1, Integer.MIN_VALUE}) {
                assertDoesNotThrow(() -> server.observeSessionDepth("TEST", true, price, 100000, timestamp));
                assertDoesNotThrow(() -> server.observeSessionDepth("TEST", true, price, 0, timestamp));
            }
        }
        // Replay book state may already contain invalid levels before the first regular-session event.
        book.update(true, 0, 100000); book.update(false, -1, 200000);
        book.update(true, 10000, 2000);
        assertDoesNotThrow(() -> server.observeSessionTime("TEST", open));
        server.updateRegularSessionHighLow("TEST", 100, open);
        assertEquals(2000, server.entrySizeCap("TEST").get("largestOrderSize").getAsInt());
        assertDoesNotThrow(() -> server.observeSessionDepth("TEST", true, 10000, 5000, open + 1));
        assertEquals(5000, server.entrySizeCap("TEST").get("largestOrderSize").getAsInt());
    }
    @Test void removedOrdersBecomeEligibleWhenRangeExpandsIncludingBothBoundaries() {
        OrderBookState book = new OrderBookState(); SessionOrderSizeTracker tracker = new SessionOrderSizeTracker();
        long open = time("2026-10-16T09:30:00");
        tracker.onDepth(true, 99, 10000, open, book, .01);
        tracker.onDepth(true, 99, 0, open + 1, book, .01);
        tracker.onDepth(true, 101, 5000, open + 2, book, .01);
        tracker.onDepth(true, 103, 15000, open + 3, book, .01);
        assertEquals(5000, tracker.largest("2026-10-16", 100, 102));
        assertEquals(10000, tracker.largest("2026-10-16", 99, 102));
        assertEquals(15000, tracker.largest("2026-10-16", 99, 103));
        tracker.onDepth(true, 103, 100, open + 4, book, .01);
        assertEquals(15000, tracker.largest("2026-10-16", 99, 103));
    }
    @Test void premarketClearedOrdersAndAfterHoursDoNotCountAndNextDayResets() {
        OrderBookState book = new OrderBookState(); SessionOrderSizeTracker tracker = new SessionOrderSizeTracker();
        tracker.onDepth(true, 100, 50000, time("2026-10-16T09:29:59"), book, .01);
        book.update(true, 10000, 2000);
        tracker.advance(time("2026-10-16T09:30:00"), book, .01);
        assertEquals(2000, tracker.largest("2026-10-16", 100, 100));
        tracker.onDepth(true, 100, 100000, time("2026-10-16T16:00:00"), book, .01);
        assertEquals(2000, tracker.largest("2026-10-16", 100, 100));
        tracker.advance(time("2026-10-19T09:00:00"), book, .01);
        assertEquals(0, tracker.largest("2026-10-16", 100, 100));
        assertEquals(0, tracker.largest("2026-10-19", 100, 100));
    }
    @Test void serverUsesUnfilteredDepthAndLiveRangeRatherThanOnlyTargetWalls() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 0);
        OrderBookState book = new OrderBookState(); server.registerSymbol("TEST", book, .01);
        long open = time("2026-10-16T09:30:00");
        server.observeSessionDepth("TEST", true, 9900, 10000, open);
        server.observeSessionDepth("TEST", true, 9900, 0, open + 1);
        server.observeSessionDepth("TEST", true, 10000, 2000, open + 2);
        server.updateRegularSessionHighLow("TEST", 100, open + 3);
        assertEquals(.4, server.entrySizeCap("TEST").get("maxRiskMultiple").getAsDouble());
        server.updateRegularSessionHighLow("TEST", 99, open + 4);
        assertEquals(2, server.entrySizeCap("TEST").get("maxRiskMultiple").getAsDouble());
        server.unregisterSymbol("TEST");
        assertEquals(0, server.entrySizeCap("TEST").get("maxRiskMultiple").getAsDouble());
    }
}
