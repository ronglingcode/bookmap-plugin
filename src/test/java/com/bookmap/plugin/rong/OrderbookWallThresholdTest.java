package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.bookmap.plugin.rong.patterns.PatternType;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

class OrderbookWallThresholdTest {

    @Test
    void displayThresholdUsesHigherOfFloorAndPercentile() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        OrderBookState orderBook = new OrderBookState();
        orderBook.update(false, 10_010, 5_000);
        orderBook.update(false, 10_020, 5_200);
        orderBook.update(false, 10_030, 6_000);
        orderBook.update(false, 10_040, 200_000);
        orderBook.update(false, 10_050, 200_000);
        orderBook.update(false, 10_060, 200_000);
        server.registerSymbol("WEN", orderBook, 0.01);

        SignalWebSocketServer.OrderbookWallThreshold threshold =
                server.getOrderbookWallThreshold("WEN", 5_000);
        assertTrue(threshold.isAvailable());
        assertEquals(90.0, threshold.getPercentile(), 0.00001);
        assertEquals(5_000, threshold.getAbsoluteMinSize());
        assertEquals(200_000, threshold.getPercentileMinSize());
        assertEquals(200_000, threshold.getEffectiveMinSize());
        assertEquals(Arrays.asList(200_000, 200_000, 200_000), threshold.getLargestLevelSizes());
    }

    @Test
    void sharedSizeThresholdUsesHigherOfFloorAndPercentile() {
        OrderBookState orderBook = new OrderBookState();
        orderBook.update(false, 10_010, 5_000);
        orderBook.update(false, 10_020, 6_000);
        orderBook.update(false, 10_030, 20_000);

        assertEquals(20_000, orderBook.getSizeThreshold(5_000, 90));
        assertEquals(25_000, orderBook.getSizeThreshold(25_000, 90));
        assertEquals(20_000, OrderBookState.combineSizeThresholds(5_000, 20_000));
    }

    @Test
    void largestLevelSizesSpanBothSidesAndRetainDuplicates() {
        OrderBookState orderBook = new OrderBookState();
        orderBook.update(true, 10_000, 8_000);
        orderBook.update(true, 9_990, 20_000);
        orderBook.update(false, 10_010, 20_000);
        orderBook.update(false, 10_020, 12_000);

        assertEquals(Arrays.asList(20_000, 20_000, 12_000), orderBook.getLargestLevelSizes(3));

        orderBook.update(false, 10_010, 0);

        assertEquals(Arrays.asList(20_000, 12_000, 8_000), orderBook.getLargestLevelSizes(3));
    }

    @Test
    void wallBreakAlertAvailabilityFollowsBookmapTradeButtons() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);

        JsonObject config = new JsonObject();
        config.addProperty("type", "trade_button_config");
        config.addProperty("symbol", "WEN");
        JsonArray tradebooks = new JsonArray();
        tradebooks.add(tradebook("GapAndGoBookmapOfferWallBreakout", true, "0.25R"));
        tradebooks.add(tradebook("GapAndCrapBookmapBidWallBreakdown", false, "0.25R"));
        config.add("tradebooks", tradebooks);

        server.onMessage(null, config.toString());

        assertTrue(server.hasEnabledWallBreakTradeButton("WEN", false));
        assertTrue(server.hasEnabledWallBreakTradeButton("WEN", true));
    }

    @Test
    void wallBreakAlertAvailabilityRejectsWrongSideTradeButtons() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);

        JsonObject config = new JsonObject();
        config.addProperty("type", "trade_button_config");
        config.addProperty("symbol", "WEN");
        JsonArray tradebooks = new JsonArray();
        tradebooks.add(tradebook("GapAndGoBookmapOfferWallBreakout", false, "0.25R"));
        tradebooks.add(tradebook("GapAndCrapBookmapBidWallBreakdown", true, "0.25R"));
        config.add("tradebooks", tradebooks);

        server.onMessage(null, config.toString());

        assertFalse(server.hasEnabledWallBreakTradeButton("WEN", false));
        assertFalse(server.hasEnabledWallBreakTradeButton("WEN", true));
    }

    @Test
    void tradeButtonConfigRejectsMissingOrNonBooleanSide() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        JsonObject config = new JsonObject();
        config.addProperty("type", "trade_button_config");
        config.addProperty("symbol", "WEN");
        JsonArray tradebooks = new JsonArray();
        JsonObject missingSide = tradebook("GapAndGoBookmapOfferWallBreakout", true, "0.25R");
        missingSide.remove("sideIsLong");
        JsonObject stringSide = tradebook("GapAndCrapBookmapBidWallBreakdown", false, "0.25R");
        stringSide.addProperty("sideIsLong", "false");
        tradebooks.add(missingSide);
        tradebooks.add(stringSide);
        config.add("tradebooks", tradebooks);

        server.onMessage(null, config.toString());

        assertFalse(server.hasEnabledWallBreakTradeButton("WEN", false));
        assertFalse(server.hasEnabledWallBreakTradeButton("WEN", true));
    }

    @Test
    void patternEligibilityUsesActiveMatchingBreakAndReversalTradebooks() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        JsonObject config = new JsonObject();
        config.addProperty("type", "trade_button_config");
        config.addProperty("symbol", "WEN");
        JsonArray tradebooks = new JsonArray();
        tradebooks.add(tradebook("GapAndGoBookmapOfferWallBreakout", true, "0.25R"));
        tradebooks.add(tradebook("GapGiveAndGoBookmapReversal", true, "0.25R"));
        tradebooks.add(tradebook("GapAndCrapOfferStepDownReappear", false, "0.25R"));
        config.add("tradebooks", tradebooks);
        server.onMessage(null, config.toString());

        assertTrue(server.hasEnabledPatternTradebook("WEN", PatternType.OFFER_WALL_BREAKOUT));
        assertTrue(server.hasEnabledPatternTradebook("WEN", PatternType.BID_REAPPEAR));
        assertTrue(server.hasEnabledPatternTradebook("WEN", PatternType.BID_STEP_UP));
        assertTrue(server.hasEnabledPatternTradebook("WEN", PatternType.BID_V_SHAPE_RECOVERY));
        assertTrue(server.hasEnabledPatternTradebook("WEN", PatternType.OFFER_REAPPEAR));
        assertTrue(server.hasEnabledPatternTradebook("WEN", PatternType.OFFER_STEP_DOWN));
        assertTrue(server.hasEnabledPatternTradebook("WEN", PatternType.OFFER_V_SHAPE_REJECTION));
        assertFalse(server.hasEnabledPatternTradebook("WEN", PatternType.BID_WALL_BREAKDOWN));
    }

    @Test
    void primaryWallReversalFollowsTradeButtonOrderAndSide() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        JsonObject config = new JsonObject();
        config.addProperty("type", "trade_button_config");
        config.addProperty("symbol", "WEN");
        JsonArray tradebooks = new JsonArray();
        tradebooks.add(tradebook("GapGiveAndGoBookmapReversal", true, "0.25 R"));
        tradebooks.add(tradebook("RangeBoundBidReversal", true, "0.025 R"));
        tradebooks.add(tradebook("GapAndCrapOfferStepDownReappear", false, "0.25 R"));
        config.add("tradebooks", tradebooks);
        server.onMessage(null, config.toString());

        assertEquals(
                "GapGiveAndGoBookmapReversal",
                server.getPrimaryWallReversalTradebook("WEN", true).getTradebookId());
        assertEquals(
                "GapAndCrapOfferStepDownReappear",
                server.getPrimaryWallReversalTradebook("WEN", false).getTradebookId());
    }

    private static JsonObject tradebook(String tradebookId, boolean sideIsLong, String entryMethod) {
        JsonObject tradebook = new JsonObject();
        tradebook.addProperty("id", tradebookId);
        tradebook.addProperty("label", tradebookId);
        tradebook.addProperty("sideIsLong", sideIsLong);
        tradebook.addProperty("tradebookId", tradebookId);
        tradebook.addProperty("tradebookName", tradebookId);
        JsonArray entryMethods = new JsonArray();
        entryMethods.add(entryMethod);
        tradebook.add("entryMethods", entryMethods);
        return tradebook;
    }
}
