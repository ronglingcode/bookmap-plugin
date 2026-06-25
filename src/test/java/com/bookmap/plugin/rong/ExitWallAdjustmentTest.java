package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExitWallAdjustmentTest {

    @Test
    void longPositionTargetsNearestAskWallAtThresholdTwoCentsBelow() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90, 1000);
        OrderBookState orderBook = new OrderBookState();
        orderBook.update(false, 10_010, 5_000);
        orderBook.update(false, 10_050, 6_200);
        server.registerSymbol("SMCI", orderBook, 0.01);
        server.onMessage(null, accountState("SMCI", 100, "SELL", "limit-long-1", 101.00));

        SignalWebSocketServer.ExitWallAdjustment adjustment =
                server.resolveExitWallAdjustment("SMCI", 1, 5_000, 0.02);

        assertTrue(adjustment.isAvailable(), adjustment.getReason());
        assertTrue(adjustment.isLongPosition());
        assertFalse(adjustment.isBidWall());
        assertEquals(10_010, adjustment.getWallPriceTick());
        assertEquals(100.10, adjustment.getWallPrice(), 0.00001);
        assertEquals(100.08, adjustment.getTargetPrice(), 0.00001);
        assertEquals("limit-long-1", adjustment.getLimitOrderId());
    }

    @Test
    void shortPositionTargetsNearestBidWallTwoCentsAbove() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90, 1000);
        OrderBookState orderBook = new OrderBookState();
        orderBook.update(true, 9_990, 4_900);
        orderBook.update(true, 9_960, 7_100);
        server.registerSymbol("SMCI", orderBook, 0.01);
        server.onMessage(null, accountState("SMCI", -100, "BUY", "limit-short-1", 99.40));

        SignalWebSocketServer.ExitWallAdjustment adjustment =
                server.resolveExitWallAdjustment("SMCI", 1, 5_000, 0.02);

        assertTrue(adjustment.isAvailable(), adjustment.getReason());
        assertFalse(adjustment.isLongPosition());
        assertTrue(adjustment.isBidWall());
        assertEquals(9_960, adjustment.getWallPriceTick());
        assertEquals(99.60, adjustment.getWallPrice(), 0.00001);
        assertEquals(99.62, adjustment.getTargetPrice(), 0.00001);
        assertEquals("limit-short-1", adjustment.getLimitOrderId());
    }

    private static String accountState(
            String symbol,
            double netQuantity,
            String limitSide,
            String limitOrderId,
            double limitPrice) {
        return "{"
                + "\"type\":\"account_state\","
                + "\"symbol\":\"" + symbol + "\","
                + "\"position\":{\"netQuantity\":" + netQuantity + ",\"averagePrice\":100.0},"
                + "\"openOrders\":[{"
                + "\"role\":\"LIMIT\","
                + "\"orderType\":\"LIMIT\","
                + "\"side\":\"" + limitSide + "\","
                + "\"orderId\":\"" + limitOrderId + "\","
                + "\"quantity\":1,"
                + "\"price\":" + limitPrice + ","
                + "\"pairIndex\":1"
                + "}],"
                + "\"timestamp\":1"
                + "}";
    }
}
