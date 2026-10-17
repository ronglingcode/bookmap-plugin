package com.bookmap.plugin.rong.tradebuttons;

import com.bookmap.plugin.rong.SignalWebSocketServer;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.BookmapSizeCap.Mode;
import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StrictLiquidityModeTest {
    @Test void selectorChangesTheRuleUsedBySizingAndShowsBlockedStateAndStopException() throws Exception {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 0);
        AtomicInteger refreshes = new AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            var selector = TradeButtonWindow.createLiquidityModeSelector("TEST", server, refreshes::incrementAndGet);
            assertEquals(Mode.STRICT, selector.getSelectedItem());
            assertTrue(TradeButtonWindow.liquidityStatusText(server.entrySizeCap("TEST")).contains("ENTRY BLOCKED"));
            selector.setSelectedItem(Mode.PROPORTIONAL);
            assertEquals("PROPORTIONAL", server.entrySizeCap("TEST").get("mode").getAsString());
            assertTrue(TradeButtonWindow.liquidityStatusText(server.entrySizeCap("TEST")).contains("Cap: 0.00R"));
            selector.setSelectedItem(Mode.STRICT);
            assertEquals(Mode.STRICT, server.entryLiquidityMode("TEST")); assertEquals(2, refreshes.get());
            JsonObject qualified = server.entrySizeCap("TEST");
            qualified.addProperty("available", true); qualified.addProperty("largestOrderSize", 5000);
            assertTrue(TradeButtonWindow.liquidityStatusText(qualified).contains("threshold met"));
            assertFalse(TradeButtonWindow.liquidityStatusText(qualified).contains("ENTRY BLOCKED"));
            assertTrue(TradeButtonWindow.liquidityStatusText(qualified).contains("trigger price"));
        });
    }
}
