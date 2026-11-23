package com.bookmap.plugin.rong;

import org.junit.jupiter.api.Test;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import static com.bookmap.plugin.rong.PatternObservationActivationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class BackgroundMarketCallbacksTest {
    @Test void stateUpdatesOnEveryArrivalAndHistoryAndWallBoundariesCannotBeCoalescedAway() throws Exception {
        Object saved = field("sharedServer").get(null);
        SignalWebSocketServer server = new SignalWebSocketServer(0, 0,
                () -> java.time.Instant.parse("2026-11-23T14:30:00Z").toEpochMilli());
        RongPlugin plugin = plugin("TEST");
        try (var reducer = new MarketDataDispatcher("TEST", message -> fail(message))) {
            field("sharedServer").set(null, server); OrderBookState book = new OrderBookState();
            field("orderBook").set(plugin, book);
            field("instrumentInfo").set(plugin, new InstrumentInfo("TEST", "NASDAQ", "STOCKS", .01, 1, "TEST", true));
            field("initialized").setBoolean(plugin, true); field("marketDataDispatcher").set(plugin, reducer);
            server.registerSymbol("TEST", book, .01); server.registerMarketDataStatus("TEST", reducer::status);
            server.beginSessionHistory("TEST"); plugin.onTimestamp(SessionOrderSizeTrackerTest.OPEN);
            plugin.onDepth(false, 10000, 10000);
            assertEquals(10000, book.getSizeAt(false, 10000));
            for (int i = 0; i < 50_000; i++) plugin.onTrade(10000, 3, new TradeInfo(false, true));
            assertEquals(150_000, server.entrySizeCap("TEST").get("largestOrderSize").getAsLong());
            assertTrue(server.entrySizeCap("TEST").get("historyLoading").getAsBoolean());
            plugin.onSnapshotEnd(); plugin.onRealtimeStart();
            assertFalse(server.entrySizeCap("TEST").get("historyLoading").getAsBoolean());
            plugin.onDepth(false, 10000, 0); plugin.onDepth(false, 10000, 10000);
            plugin.onTrade(10000, 9000, new TradeInfo(false, true));
            assertEquals(150_000, server.entrySizeCap("TEST").get("largestOrderSize").getAsLong(), "Refill starts a new wall episode");
            assertEquals(0, reducer.status().get("queueDepth").getAsInt());
            assertTrue(server.entrySizeCap("TEST").get("available").getAsBoolean());
        } finally { field("initialized").setBoolean(plugin, false); field("sharedServer").set(null, saved); server.shutdown(); }
    }
}
