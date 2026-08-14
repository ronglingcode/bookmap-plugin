package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExitWallAdjustmentTest {

    @Test
    void longPositionSkipsNearestAskBelowEffectiveThreshold() {
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
        assertEquals(6_200, adjustment.getSizeThreshold());
        assertEquals(10_050, adjustment.getWallPriceTick());
        assertEquals(100.50, adjustment.getWallPrice(), 0.00001);
        assertEquals(100.48, adjustment.getTargetPrice(), 0.00001);
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
        assertEquals(7_100, adjustment.getSizeThreshold());
        assertEquals(9_960, adjustment.getWallPriceTick());
        assertEquals(99.60, adjustment.getWallPrice(), 0.00001);
        assertEquals(99.62, adjustment.getTargetPrice(), 0.00001);
        assertEquals("limit-short-1", adjustment.getLimitOrderId());
    }

    @Test
    void wallOutSelectsFirstPairWithSmallestQuantity() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90, 1000);
        OrderBookState orderBook = new OrderBookState();
        orderBook.update(false, 10_050, 6_200);
        server.registerSymbol("SMCI", orderBook, 0.01);
        server.onMessage(null, accountStateWithOrders(
                "SMCI",
                100,
                limitOrder("SELL", "limit-1", 20, 101.00, 1),
                limitOrder("SELL", "limit-2", 5, 102.00, 2),
                limitOrder("SELL", "limit-3", 5, 103.00, 3)));

        SignalWebSocketServer.ExitWallAdjustment adjustment =
                server.resolveSmallestQuantityExitWallAdjustment("SMCI", 5_000, 0.02);

        assertTrue(adjustment.isAvailable(), adjustment.getReason());
        assertEquals(2, adjustment.getPairIndex());
        assertEquals("limit-2", adjustment.getLimitOrderId());
        assertEquals(5, adjustment.getLimitOrderQuantity(), 0.00001);
    }

    private static String accountState(
            String symbol,
            double netQuantity,
            String limitSide,
            String limitOrderId,
            double limitPrice) {
        return accountStateWithOrders(
                symbol,
                netQuantity,
                limitOrder(limitSide, limitOrderId, 1, limitPrice, 1));
    }

    private static String accountStateWithOrders(
            String symbol,
            double netQuantity,
            String... orders) {
        return "{"
                + "\"type\":\"account_state\","
                + "\"symbol\":\"" + symbol + "\","
                + "\"position\":{\"netQuantity\":" + netQuantity + ",\"averagePrice\":100.0},"
                + "\"openOrders\":[" + String.join(",", orders) + "],"
                + "\"timestamp\":1"
                + "}";
    }

    private static String limitOrder(
            String limitSide,
            String limitOrderId,
            double quantity,
            double limitPrice,
            int pairIndex) {
        return "{"
                + "\"role\":\"LIMIT\","
                + "\"orderType\":\"LIMIT\","
                + "\"side\":\"" + limitSide + "\","
                + "\"orderId\":\"" + limitOrderId + "\","
                + "\"quantity\":" + quantity + ","
                + "\"price\":" + limitPrice + ","
                + "\"pairIndex\":" + pairIndex
                + "}";
    }
}
