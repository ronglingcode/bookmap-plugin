package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.SignalComposerActivationTest.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.simplified.BackfilledDataListener;
import velox.api.layer1.simplified.HistoricalModeListener;

class SessionLiquidityHistoryTest {
    private static final long OPEN = SessionOrderSizeTrackerTest.OPEN;
    private static final long SECOND = 1_000_000_000L;

    interface Scenario { void run(RongPlugin plugin, SignalWebSocketServer server) throws Exception; }

    private void history(Scenario scenario) throws Exception {
        withIsolatedActivation(() -> {
            Object saved = field("sharedServer").get(null);
            SignalWebSocketServer server = new SignalWebSocketServer(0, 0);
            try {
                field("sharedServer").set(null, server);
                RongPlugin plugin = SignalComposerCallbacksTest.callbackPlugin();
                server.registerSymbol("TEST", (OrderBookState) field("orderBook").get(plugin), .01);
                server.beginSessionHistory("TEST");
                scenario.run(plugin, server);
            } finally {
                field("sharedServer").set(null, saved);
                server.shutdown();
            }
        });
    }

    private static void trade(RongPlugin plugin, long time, int price, int size, boolean buy) {
        plugin.onTimestamp(time);
        plugin.onTrade(price, size, new TradeInfo(false, buy));
    }

    @Test void lateLoadUsesEarlierExecutedPrintsEvenWithoutHistoricalDepthOrSnapshotReadiness() throws Exception {
        history((plugin, server) -> {
            assertInstanceOf(HistoricalModeListener.class, plugin);
            assertInstanceOf(BackfilledDataListener.class, plugin);
            // Load at 10:30, then let Bookmap deliver its stored 09:30 onward data.
            plugin.onTimestamp(OPEN + 3600 * SECOND);
            trade(plugin, OPEN - SECOND, 10000, 20000, true); // Premarket cannot qualify.
            assertEquals(0, server.entrySizeCap("TEST").get("largestOrderSize").getAsLong());
            trade(plugin, OPEN + SECOND, 10000, 6000, true);
            JsonObject loading = server.entrySizeCap("TEST");
            assertEquals(6000, loading.get("largestOrderSize").getAsLong());
            assertEquals("historicalTrade", loading.get("evidenceBasis").getAsString());
            assertFalse(loading.get("available").getAsBoolean());
            assertTrue(loading.get("historyLoading").getAsBoolean());
            assertFalse(field("patternMonitorSnapshotComplete").getBoolean(plugin));
            plugin.onTimestamp(OPEN + 3600 * SECOND);
            plugin.onRealtimeStart();
            JsonObject ready = server.entrySizeCap("TEST");
            assertTrue(ready.get("available").getAsBoolean());
            assertFalse(ready.get("historyLoading").getAsBoolean());
            assertEquals("offer", ready.get("largestOrderSide").getAsString());
            assertEquals(100, ready.get("largestOrderPrice").getAsDouble());
            assertEquals((OPEN + SECOND) / 1_000_000L, ready.get("largestOrderObservedAt").getAsLong());
            // The history fallback ends at the handoff; unobserved live walls cannot add evidence.
            trade(plugin, OPEN + 3601 * SECOND, 10100, 20000, false);
            assertEquals(6000, server.entrySizeCap("TEST").get("largestOrderSize").getAsLong());
        });
    }

    @Test void historicalPrintsAreNotSummedAcrossPricesSidesOrUnrelatedSamePriceTrades() throws Exception {
        history((plugin, server) -> {
            trade(plugin, OPEN, 10000, 3000, true);
            trade(plugin, OPEN + SECOND, 10000, 3000, true);
            trade(plugin, OPEN + 2 * SECOND, 10000, 3000, false);
            trade(plugin, OPEN + 3 * SECOND, 10100, 3000, true);
            assertEquals(3000, server.entrySizeCap("TEST").get("largestOrderSize").getAsLong());
            trade(plugin, OPEN + 4 * SECOND, 10200, 5000, false); // Equality qualifies, bid side too.
            assertEquals(5000, server.entrySizeCap("TEST").get("largestOrderSize").getAsLong());
            assertEquals("bid", server.entrySizeCap("TEST").get("largestOrderSide").getAsString());
            plugin.onTimestamp(OPEN + 5 * SECOND);
            plugin.onTrade(10300, 20000, null); // Missing side cannot fabricate evidence.
            assertEquals(5000, server.entrySizeCap("TEST").get("largestOrderSize").getAsLong());
        });
    }

    @Test void historicalAfterHoursAndPriorDayPrintsCannotQualifyCurrentSession() throws Exception {
        history((plugin, server) -> {
            trade(plugin, OPEN - 86400 * SECOND, 10000, 20000, true);
            trade(plugin, OPEN, 10000, 3000, true);
            trade(plugin, OPEN + 23400 * SECOND, 10000, 30000, true); // 16:00 Eastern, excluded.
            assertEquals(3000, server.entrySizeCap("TEST").get("largestOrderSize").getAsLong());
            assertFalse(server.entrySizeCap("TEST").get("available").getAsBoolean());
            plugin.onTimestamp(OPEN + 3 * 86400 * SECOND);
            plugin.onRealtimeStart();
            assertEquals(0, server.entrySizeCap("TEST").get("largestOrderSize").getAsLong());
            assertTrue(server.entrySizeCap("TEST").get("available").getAsBoolean());
        });
    }

    @Test void historicalWallPartialsContinueIntoLiveWithoutDoubleCountingAtTheHandoff() throws Exception {
        history((plugin, server) -> {
            plugin.onTimestamp(OPEN);
            plugin.onDepth(false, 10000, 8000);
            trade(plugin, OPEN + SECOND, 10000, 2000, true);
            plugin.onDepth(false, 10000, 6000);
            trade(plugin, OPEN + 2 * SECOND, 10000, 3000, true);
            assertEquals(5000, server.entrySizeCap("TEST").get("largestOrderSize").getAsLong());
            plugin.onRealtimeStart();
            assertEquals(5000, server.entrySizeCap("TEST").get("largestOrderSize").getAsLong());
            trade(plugin, OPEN + 3 * SECOND, 10000, 1000, true);
            assertEquals(6000, server.entrySizeCap("TEST").get("largestOrderSize").getAsLong());
        });
    }

    @Test void seeksAndNewDatesDiscardHistoricalQualificationAndDetachingClearsLoadingState() throws Exception {
        history((plugin, server) -> {
            trade(plugin, OPEN + 60 * SECOND, 10000, 6000, true);
            plugin.onTimestamp(OPEN);
            assertEquals(0, server.entrySizeCap("TEST").get("largestOrderSize").getAsLong());
            trade(plugin, OPEN + SECOND, 10000, 7000, true);
            plugin.onTimestamp(OPEN + 3 * 86400 * SECOND); // Monday, new session.
            assertEquals(0, server.entrySizeCap("TEST").get("largestOrderSize").getAsLong());
            server.unregisterSymbol("TEST");
            assertFalse(server.entrySizeCap("TEST").get("historyLoading").getAsBoolean());
        });
    }
}
