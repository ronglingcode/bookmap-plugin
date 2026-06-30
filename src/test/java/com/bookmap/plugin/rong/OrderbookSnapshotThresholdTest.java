package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

class OrderbookSnapshotThresholdTest {

    @Test
    void snapshotKeepsProtectedFiveThousandLevelsWhilePercentileFiltersNoise() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90, 1000);
        OrderBookState orderBook = new OrderBookState();
        orderBook.update(false, 10_010, 5_000);
        orderBook.update(false, 10_020, 5_200);
        orderBook.update(false, 10_030, 6_000);
        orderBook.update(false, 10_040, 200_000);
        orderBook.update(false, 10_050, 200_000);
        orderBook.update(false, 10_060, 200_000);
        server.registerSymbol("WEN", orderBook, 0.01);

        JsonObject target = new JsonObject();
        assertTrue(server.appendOrderbookSnapshot("WEN", target, 5_000, 2));

        JsonObject snapshot = target.getAsJsonObject("orderbook");
        assertEquals(5_000, snapshot.get("absoluteWallThreshold").getAsInt());
        assertEquals(90.0, snapshot.get("percentile").getAsDouble(), 0.00001);
        assertEquals(200_000, snapshot.get("percentileWallThreshold").getAsInt());
        assertEquals(200_000, snapshot.get("effectiveWallThreshold").getAsInt());
        assertEquals(2, snapshot.get("protectedAbsoluteWallLevels").getAsInt());
        assertEquals(
                Arrays.asList(5_000, 5_200, 200_000, 200_000, 200_000),
                sizes(snapshot.getAsJsonArray("largeAsks")));

        SignalWebSocketServer.OrderbookWallThreshold threshold =
                server.getOrderbookWallThreshold("WEN", 5_000);
        assertTrue(threshold.isAvailable());
        assertEquals(90.0, threshold.getPercentile(), 0.00001);
        assertEquals(5_000, threshold.getAbsoluteMinSize());
        assertEquals(200_000, threshold.getPercentileMinSize());
        assertEquals(200_000, threshold.getEffectiveMinSize());
    }

    @Test
    void wallBreakAlertAvailabilityFollowsBookmapTradeButtons() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90, 1000);

        JsonObject config = new JsonObject();
        config.addProperty("type", "trade_button_config");
        config.addProperty("symbol", "WEN");
        JsonArray tradebooks = new JsonArray();
        tradebooks.add(tradebook("GapAndGoBookmapOfferWallBreakout", "long", "0.25R"));
        tradebooks.add(tradebook("GapAndCrapBookmapBidWallBreakdown", "short", "0.25R"));
        config.add("tradebooks", tradebooks);

        server.onMessage(null, config.toString());

        assertTrue(server.hasEnabledWallBreakTradeButton("WEN", false));
        assertTrue(server.hasEnabledWallBreakTradeButton("WEN", true));
    }

    @Test
    void wallBreakAlertAvailabilityRejectsWrongSideTradeButtons() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90, 1000);

        JsonObject config = new JsonObject();
        config.addProperty("type", "trade_button_config");
        config.addProperty("symbol", "WEN");
        JsonArray tradebooks = new JsonArray();
        tradebooks.add(tradebook("GapAndGoBookmapOfferWallBreakout", "short", "0.25R"));
        tradebooks.add(tradebook("GapAndCrapBookmapBidWallBreakdown", "long", "0.25R"));
        config.add("tradebooks", tradebooks);

        server.onMessage(null, config.toString());

        assertFalse(server.hasEnabledWallBreakTradeButton("WEN", false));
        assertFalse(server.hasEnabledWallBreakTradeButton("WEN", true));
    }

    private static List<Integer> sizes(JsonArray levels) {
        return levels.asList().stream()
                .map(level -> level.getAsJsonArray().get(1).getAsInt())
                .collect(Collectors.toList());
    }

    private static JsonObject tradebook(String tradebookId, String side, String entryMethod) {
        JsonObject tradebook = new JsonObject();
        tradebook.addProperty("id", tradebookId);
        tradebook.addProperty("label", tradebookId);
        tradebook.addProperty("side", side);
        tradebook.addProperty("tradebookId", tradebookId);
        tradebook.addProperty("tradebookName", tradebookId);
        JsonArray entryMethods = new JsonArray();
        entryMethods.add(entryMethod);
        tradebook.add("entryMethods", entryMethods);
        return tradebook;
    }
}
