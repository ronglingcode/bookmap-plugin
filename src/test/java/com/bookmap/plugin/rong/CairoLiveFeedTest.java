package com.bookmap.plugin.rong;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CairoLiveFeedTest {
    @Test void depthUsesLiveTimestampAndCanLookUpASmallerExactPrice() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 0);
        try {
            OrderBookState book = new OrderBookState();
            server.registerSymbol("META", book, 0.01);
            server.setWallThresholdFloor(() -> 5000);
            book.update(true, 18831, 7143);
            book.update(true, 18830, 12000);
            book.update(false, 18834, 300);
            JsonObject request = new JsonObject();
            request.addProperty("symbol", "META");
            request.addProperty("requestId", "test-request");
            assertFalse(server.cairoDepthResponse(request).has("asOf"));
            server.markCairoLiveDepth("META", 12345);
            JsonObject snapshot = server.cairoDepthResponse(request);
            assertEquals("test-request", snapshot.get("requestId").getAsString());
            assertEquals("12345", snapshot.get("asOf").getAsString());
            JsonObject bookPayload = snapshot.getAsJsonObject("orderbook");
            assertEquals(12000, bookPayload.getAsJsonArray("largeBids").get(0).getAsJsonArray().get(1).getAsInt());
            assertEquals(0, bookPayload.getAsJsonArray("largeAsks").size());
            request.addProperty("price", 188.34);
            JsonObject exact = server.cairoDepthResponse(request).getAsJsonObject("orderbook");
            assertEquals(300, exact.getAsJsonArray("largeAsks").get(0).getAsJsonArray().get(1).getAsInt());
            request.addProperty("mode", "replay");
            assertFalse(server.cairoDepthResponse(request).has("asOf"));
            server.observeCairoReplayDepth("META", false, 18834, 765, 999);
            assertEquals("999", server.cairoDepthResponse(request).get("asOf").getAsString());
            assertEquals(765, server.cairoDepthResponse(request).getAsJsonObject("orderbook")
                    .getAsJsonArray("largeAsks").get(0).getAsJsonArray().get(1).getAsInt());
            server.resetCairoReplayDepth("META");
            assertFalse(server.cairoDepthResponse(request).has("asOf"));
            server.unregisterSymbol("META");
            assertFalse(server.cairoDepthResponse(request).has("asOf"));
        } finally { server.shutdown(); }
    }

    @Test void replayResetInvalidatesQueuedPricesAndIsDeliveredOnReconnect() {
        List<String> messages = new ArrayList<>();
        CairoLiveFeed feed = new CairoLiveFeed(messages::add);
        feed.chart("META", "replay", true);
        JsonObject old = feed.makeTrade("META", "replay", 103, 100, 1);
        JsonObject reset = JsonParser.parseString("{\"type\":\"cairo_replay_reset\",\"symbol\":\"META\",\"epoch\":2}").getAsJsonObject();
        feed.replay(reset);
        feed.trade(old);
        assertEquals(2, messages.size());
        List<String> snapshot = new ArrayList<>();
        feed.snapshot(snapshot::add);
        assertEquals(2, snapshot.size());
        assertTrue(snapshot.get(1).contains("\"delivery\":\"snapshot\""));
        JsonObject fresh = feed.makeTrade("META", "replay", 99, 90, 1);
        feed.trade(fresh);
        assertEquals(3, messages.size());
        assertEquals(2, fresh.get("tradeSequence").getAsInt());
    }

    @Test void reconciledAccountProjectionOnlyExportsSelectedFields() {
        List<String> messages = new ArrayList<>();
        CairoLiveFeed feed = new CairoLiveFeed(messages::add);
        JsonObject account = JsonParser.parseString("{\"symbol\":\"META\",\"position\":{\"netQuantity\":3,\"averagePrice\":101,\"privateField\":\"private\"},\"openOrders\":[{\"role\":\"STOP\",\"quantity\":3,\"price\":98}],\"executions\":[{\"orderID\":\"broker-internal\",\"isBuy\":true,\"positionEffectIsOpen\":true,\"quantity\":3,\"price\":101,\"timestamp\":20}]}").getAsJsonObject();
        feed.account(account, 21);
        feed.account(account, 21);
        assertEquals(1, messages.size());
        assertFalse(messages.get(0).contains("broker-internal"));
        assertFalse(messages.get(0).contains("privateField"));
        assertEquals(98, JsonParser.parseString(messages.get(0)).getAsJsonObject().getAsJsonArray("workingStops").get(0).getAsJsonObject().get("price").getAsInt());
    }

    @Test void minuteBarsUseAllObservedTradesAndClearOnRewind() {
        CairoLiveFeed feed = new CairoLiveFeed(ignored -> {});
        long minute = java.time.Instant.parse("2025-12-04T14:30:00Z").toEpochMilli();
        long start = minute * 1_000_000L;
        feed.chart("META", "replay", true);
        feed.chart("NVDA", "replay", true);
        feed.makeTrade("META", "replay", 103, start + 2_000_000L, 80);
        feed.makeTrade("META", "replay", 101, start + 1_000_000L, 20);
        feed.makeTrade("META", "replay", 102, start + 3_000_000L, 10);
        feed.makeTrade("NVDA", "replay", 156, start, 100);
        JsonObject response = new JsonObject();
        feed.minuteBars(response, "META", "replay");
        assertEquals(minute + 3, response.get("asOf").getAsLong());
        assertEquals(JsonParser.parseString("[[" + minute + ",101,103,101,102,110]]"), response.get("bars"));
        feed.replay(JsonParser.parseString("{\"type\":\"cairo_replay_reset\",\"symbol\":\"META\"}").getAsJsonObject());
        JsonObject reset = new JsonObject();
        feed.minuteBars(reset, "META", "replay");
        assertFalse(reset.has("bars"));
        feed.minuteBars(reset, "NVDA", "live");
        assertFalse(reset.has("bars"));
    }
}
