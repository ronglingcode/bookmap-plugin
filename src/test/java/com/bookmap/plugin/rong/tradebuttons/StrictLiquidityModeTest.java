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
        JsonObject proportional = server.entrySizeCap("TEST").deepCopy();
        proportional.addProperty("mode", "PROPORTIONAL");
        assertTrue(TradeButtonWindow.liquidityStatusText(proportional).contains("Cap: 0.00R"));
        JsonObject qualified = server.entrySizeCap("TEST");
        qualified.addProperty("available", true); qualified.addProperty("largestOrderSize", 5000);
        assertTrue(TradeButtonWindow.liquidityStatusText(qualified).contains("threshold met"));
        assertFalse(TradeButtonWindow.liquidityStatusText(qualified).contains("ENTRY BLOCKED"));
        assertTrue(TradeButtonWindow.liquidityStatusText(qualified).contains("trigger price"));
    }
}
