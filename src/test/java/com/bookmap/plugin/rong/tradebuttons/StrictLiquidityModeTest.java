package com.bookmap.plugin.rong.tradebuttons;

import com.bookmap.plugin.rong.SignalWebSocketServer;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StrictLiquidityModeTest {
    @Test void dellStatusExplainsWhitelistExemptionBeforeCutoff() {
        var server = new SignalWebSocketServer(0, 0,
                () -> java.time.Instant.parse("2026-11-25T14:30:00Z").toEpochMilli());
        try {
            String text = TradeButtonWindow.liquidityStatusText(server.entrySizeCap("DELL:NYSE:STOCKS@BMD"));
            assertTrue(text.contains("Liquidity requirement OFF"));
            assertTrue(text.contains("whitelist"));
            assertFalse(text.contains("09:35"));
            assertFalse(text.contains("ENTRY BLOCKED"));
            assertTrue(TradeButtonWindow.liquidityStatusText(server.entrySizeCap("NVDA")).contains("ENTRY BLOCKED"));
        } finally { server.shutdown(); }
    }

    @Test void failedWorkerOverridesHistoryLoadingAndNeverClaimsZeroFills() {
        JsonObject cap = new SignalWebSocketServer(0, 0,
                () -> java.time.Instant.parse("2026-11-23T14:30:00Z").toEpochMilli()).entrySizeCap("TEST");
        cap.addProperty("marketDataState", "failed"); cap.addProperty("processingDelayed", true);
        cap.addProperty("historyLoading", true);
        String text = TradeButtonWindow.liquidityStatusText(cap);
        assertTrue(text.contains("Market data ERROR")); assertTrue(text.contains("counters incomplete"));
        assertFalse(text.contains("Single wall: 0")); assertFalse(text.contains("History loading"));
        assertFalse(text.contains("CHECKING")); assertTrue(text.contains("ENTRY BLOCKED"));
        cap.addProperty("liquidityRequired", false);
        text = TradeButtonWindow.liquidityStatusText(cap);
        assertTrue(text.contains("Market data ERROR")); assertTrue(text.contains("Liquidity requirement OFF"));
        assertFalse(text.contains("ENTRY BLOCKED"));
    }

    @Test void backlogReportsQueueAndLagThenReturnsToConfirmedFillCounts() {
        JsonObject cap = new SignalWebSocketServer(0, 0,
                () -> java.time.Instant.parse("2026-11-23T14:30:00Z").toEpochMilli()).entrySizeCap("TEST");
        cap.addProperty("marketDataState", "backpressure"); cap.addProperty("processingDelayed", true);
        cap.addProperty("queueDepth", 32768); cap.addProperty("lagMillis", 432);
        cap.addProperty("largestOrderSize", 6000);
        String text = TradeButtonWindow.liquidityStatusText(cap);
        assertTrue(text.contains("CATCHING UP")); assertTrue(text.contains("32,768"));
        assertTrue(text.contains("432 ms")); assertTrue(text.contains("INCOMPLETE"));
        assertFalse(text.contains("liquidity threshold met"));
        cap.addProperty("marketDataState", "current"); cap.addProperty("processingDelayed", false);
        cap.addProperty("available", true);
        text = TradeButtonWindow.liquidityStatusText(cap);
        assertTrue(text.contains("liquidity threshold met")); assertFalse(text.contains("CATCHING UP"));
    }

    @Test void cutoffUpdatesStatusEvenWhenMarketDataWorkerIsUnhealthy() {
        var clock = new java.util.concurrent.atomic.AtomicLong(java.time.Instant.parse("2026-11-23T14:34:59.999Z").toEpochMilli());
        SignalWebSocketServer server = new SignalWebSocketServer(0, 0, clock::get);
        try {
            JsonObject cap = server.entrySizeCap("NVDA");
            assertFalse(cap.get("available").getAsBoolean());
            assertTrue(TradeButtonWindow.liquidityStatusText(cap).contains("ENTRY BLOCKED"));
            clock.incrementAndGet();
            cap = server.entrySizeCap("NVDA");
            assertFalse(cap.get("available").getAsBoolean());
            assertTrue(TradeButtonWindow.liquidityStatusText(cap).contains("Liquidity requirement OFF"));
            assertFalse(TradeButtonWindow.liquidityStatusText(cap).contains("ENTRY BLOCKED"));
        } finally { server.shutdown(); }
    }

    @Test void readOnlyStatusShowsBlockedStateQualificationAndRetainedProportionalDescription() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 0, () -> java.time.Instant.parse("2026-11-23T14:30:00Z").toEpochMilli());
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
        assertTrue(TradeButtonWindow.liquidityStatusText(qualified).contains("History loading"));
        assertFalse(TradeButtonWindow.liquidityStatusText(qualified).contains("threshold met"));
    }

    @Test void rollingRuleIndependentlyUnlocksStatusAndBothRuleResultsRemainVisible() {
        JsonObject cap = new SignalWebSocketServer(0, 0, () -> java.time.Instant.parse("2026-11-23T14:30:00Z").toEpochMilli()).entrySizeCap("TEST");
        cap.addProperty("available", true);
        cap.addProperty("requiredOrderSize", 40000);
        cap.addProperty("largestOrderSize", 30000);
        cap.addProperty("rollingOrderSize", 45000);
        String text = TradeButtonWindow.liquidityStatusText(cap);
        assertTrue(text.contains("Single wall: 30K | WAITING"));
        assertTrue(text.contains("Best 2s: 45K | MET"));
        assertTrue(text.contains("2s contributor: &ge;3.2K"));
        assertTrue(text.contains("liquidity threshold met"));
        assertFalse(text.contains("ENTRY BLOCKED"));
        cap.addProperty("available", false);
        assertTrue(TradeButtonWindow.liquidityStatusText(cap).contains("ENTRY BLOCKED"));
        cap.addProperty("available", true); cap.addProperty("historyLoading", true);
        assertTrue(TradeButtonWindow.liquidityStatusText(cap).contains("Best 2s: 45K | INCOMPLETE"));
        assertFalse(TradeButtonWindow.liquidityStatusText(cap).contains("threshold met"));
    }
}
