package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OrderbookWallThresholdFreshnessTest {

    @Test
    void recalculatesTheCurrentP97ForTheDisplay() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 97);
        OrderBookState orderBook = new OrderBookState();
        orderBook.update(false, 10_010, 5_000);
        orderBook.update(false, 10_020, 6_000);
        orderBook.update(false, 10_030, 20_000);
        server.registerSymbol("WEN", orderBook, 0.01);

        assertEquals(20_000,
                server.getOrderbookWallThreshold("WEN", 5_000).getEffectiveMinSize());

        orderBook.update(false, 10_030, 7_000);

        SignalWebSocketServer.OrderbookWallThreshold currentThreshold =
                server.getOrderbookWallThreshold("WEN", 5_000);
        assertTrue(currentThreshold.isAvailable());
        assertEquals(7_000, currentThreshold.getEffectiveMinSize());
    }
}
