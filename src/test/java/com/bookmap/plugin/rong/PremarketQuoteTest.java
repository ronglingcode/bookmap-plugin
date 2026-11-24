package com.bookmap.plugin.rong;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PremarketQuoteTest {
    @Test void usesRecentInsideMarketWithoutPlanHistoryAndRejectsUnhealthyOrDetachedChart() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        try {
            server.registerSymbol("AMD", new OrderBookState(), .01);
            assertTrue(server.premarketQuote("AMD").size() == 0);
            server.observeSessionBbo("AMD", 10000, 10002, 1);
            assertEquals(100, server.premarketQuote("AMD").get("bid").getAsDouble());
            assertEquals(100.02, server.premarketQuote("AMD").get("ask").getAsDouble());
            server.registerMarketDataHealth("AMD", () -> false); assertEquals(0, server.premarketQuote("AMD").size());
            server.registerMarketDataHealth("AMD", () -> true); server.unregisterSymbol("AMD");
            assertEquals(0, server.premarketQuote("AMD").size());
        } finally { server.shutdown(); }
    }
}
