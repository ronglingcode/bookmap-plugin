package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

class OrderbookSnapshotFreshnessTest {

    @Test
    void recalculatesAndProvidesTheCurrentP97ForEverySnapshot() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 97, 1000);
        OrderBookState orderBook = new OrderBookState();
        orderBook.update(false, 10_010, 5_000);
        orderBook.update(false, 10_020, 6_000);
        orderBook.update(false, 10_030, 20_000);
        server.registerSymbol("WEN", orderBook, 0.01);

        JsonObject firstTarget = new JsonObject();
        assertTrue(server.appendOrderbookSnapshot("WEN", firstTarget, 5_000, 0));
        assertEquals(
                20_000,
                firstTarget.getAsJsonObject("orderbook")
                        .get("effectiveWallThreshold")
                        .getAsInt());

        orderBook.update(false, 10_030, 7_000);

        JsonObject secondTarget = new JsonObject();
        assertTrue(server.appendOrderbookSnapshot("WEN", secondTarget, 5_000, 0));
        assertEquals(
                7_000,
                secondTarget.getAsJsonObject("orderbook")
                        .get("effectiveWallThreshold")
                        .getAsInt());

        SignalWebSocketServer.OrderbookWallThreshold currentThreshold =
                server.getOrderbookWallThreshold("WEN", 5_000);
        assertTrue(currentThreshold.isAvailable());
        assertEquals(7_000, currentThreshold.getEffectiveMinSize());
    }
}
