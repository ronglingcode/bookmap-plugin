package com.bookmap.plugin.rong.tradebuttons;

import com.bookmap.plugin.rong.SignalWebSocketServer;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StrictLiquidityModeTest {
    @Test void readOnlyStatusShowsBlockedStateQualificationAndRetainedProportionalDescription() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 0);
        assertEquals("STRICT", server.entrySizeCap("TEST").get("mode").getAsString());
        assertTrue(TradeButtonWindow.liquidityStatusText(server.entrySizeCap("TEST")).contains("ENTRY BLOCKED"));
        JsonObject normal = server.entrySizeCap("TEST").deepCopy();
        normal.addProperty("mode", "NORMAL_1R");
        assertTrue(TradeButtonWindow.liquidityStatusText(normal).contains("NORMAL 1R"));
        assertTrue(TradeButtonWindow.liquidityStatusText(normal).contains("No qualifying wall fills required"));
        assertFalse(TradeButtonWindow.liquidityStatusText(normal).contains("ENTRY BLOCKED"));
        JsonObject proportional = server.entrySizeCap("TEST").deepCopy();
        proportional.addProperty("mode", "PROPORTIONAL");
        assertTrue(TradeButtonWindow.liquidityStatusText(proportional).contains("Cap: 0.00R"));
        JsonObject qualified = server.entrySizeCap("TEST");
        qualified.addProperty("mode", "STRICT");
        assertTrue(TradeButtonWindow.liquidityStatusText(qualified).contains("ENTRY BLOCKED"));
        qualified.addProperty("available", true); qualified.addProperty("largestOrderSize", 5000);
        assertTrue(TradeButtonWindow.liquidityStatusText(qualified).contains("threshold met"));
        assertFalse(TradeButtonWindow.liquidityStatusText(qualified).contains("ENTRY BLOCKED"));
        assertTrue(TradeButtonWindow.liquidityStatusText(qualified).contains("Single wall: 5K | MET"));
        assertTrue(TradeButtonWindow.liquidityStatusText(qualified).contains("Best 2s: 0 | WAITING"));
        assertFalse(TradeButtonWindow.liquidityStatusText(qualified).contains("trigger price"));
        qualified.addProperty("historyLoading", true);
        assertTrue(TradeButtonWindow.liquidityStatusText(qualified).contains("checking fills since market open"));
        assertFalse(TradeButtonWindow.liquidityStatusText(qualified).contains("threshold met"));
    }

    @Test void rollingRuleIndependentlyUnlocksStatusAndBothRuleResultsRemainVisible() {
        JsonObject cap = new SignalWebSocketServer(0, 0).entrySizeCap("TEST");
        cap.addProperty("available", true);
        cap.addProperty("requiredOrderSize", 40000);
        cap.addProperty("largestOrderSize", 30000);
        cap.addProperty("rollingOrderSize", 45000);
        String text = TradeButtonWindow.liquidityStatusText(cap);
        assertTrue(text.contains("Single wall: 30K | WAITING"));
        assertTrue(text.contains("Best 2s: 45K | MET"));
        assertTrue(text.contains("2s contributor: &ge;10K"));
        assertTrue(text.contains("liquidity threshold met"));
        assertFalse(text.contains("ENTRY BLOCKED"));
        cap.addProperty("available", false);
        assertTrue(TradeButtonWindow.liquidityStatusText(cap).contains("ENTRY BLOCKED"));
        cap.addProperty("available", true); cap.addProperty("historyLoading", true);
        assertTrue(TradeButtonWindow.liquidityStatusText(cap).contains("Best 2s: 45K | CHECKING"));
        assertFalse(TradeButtonWindow.liquidityStatusText(cap).contains("threshold met"));
    }
}
