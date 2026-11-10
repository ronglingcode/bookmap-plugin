package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.PatternObservationActivationTest.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import velox.api.layer1.data.InstrumentInfo;
import com.bookmap.plugin.rong.patterns.*;
import com.google.gson.JsonObject;

class PatternEventCallbacksTest {
    static final long BASE = Instant.parse("2026-10-06T14:00:00Z").getEpochSecond() * 1_000_000_000L;
    static RongPlugin callbackPlugin() throws Exception {
        RongPlugin p = plugin("TEST");
        field("instrumentInfo").set(p, new InstrumentInfo("TEST", "NASDAQ", "STOCKS", .01, 1, "TEST", true));
        field("orderBook").set(p, new OrderBookState()); field("initialized").setBoolean(p, true);
        p.initializePatternEvents(.01); return p;
    }
    static void time(RongPlugin p, long ms) { p.onTimestamp(BASE + ms * 1_000_000L); }
    static PatternEventPipeline pipeline(RongPlugin p) throws Exception { return (PatternEventPipeline)field("patternEvents").get(p); }
    static JsonObject snapshot(RongPlugin p) throws Exception { return pipeline(p).aggregateSnapshot(new JsonObject(), false); }
    static void withdrawal(RongPlugin p) {
        time(p, 2000); p.onDepth(true, 5100, 6000); time(p, 2500);
        time(p, 3000); p.onDepth(true, 5100, 0); time(p, 3500);
    }
    @Test void callbacksExportEventsWithoutTradingRuntimeOrComposition() throws Exception {
        withIsolatedActivation(() -> {
            RongPlugin p = callbackPlugin(); time(p, 0); p.onSnapshotEnd(); p.onBbo(5100, 1, 5121, 1);
            withdrawal(p); JsonObject value = snapshot(p);
            assertEquals(1, value.getAsJsonArray("patterns").size());
            assertEquals("BIDS_CANCELLED", value.getAsJsonArray("patterns").get(0).getAsJsonObject().get("type").getAsString());
            assertFalse(value.has("signals")); assertFalse(value.has("contexts")); assertFalse(value.has("candidateStates"));
            assertNull(field("nativeTrading").get(null));
        });
    }
    @Test void preSnapshotAndFallbackCallbacksCannotProduceEvents() throws Exception {
        withIsolatedActivation(() -> {
            RongPlugin p = callbackPlugin(); time(p, 0); withdrawal(p);
            assertTrue(snapshot(p).getAsJsonArray("patterns").isEmpty());
            field("lastTimestampNs").setLong(p, 0); p.onSnapshotEnd(); p.onDepth(true, 5100, 6000); p.onDepth(true, 5100, 0);
            assertTrue(snapshot(p).getAsJsonArray("patterns").isEmpty());
        });
    }
    @Test void seekClearsEventsAndRequiresFreshReadinessWithoutOldBookSeeding() throws Exception {
        withIsolatedActivation(() -> {
            RongPlugin p = callbackPlugin(); time(p, 0); p.onSnapshotEnd(); p.onBbo(5100, 1, 5121, 1); withdrawal(p);
            assertFalse(snapshot(p).getAsJsonArray("patterns").isEmpty());
            time(p, 1000); assertTrue(snapshot(p).getAsJsonArray("patterns").isEmpty()); assertFalse(pipeline(p).usable());
            assertFalse(field("patternEventSnapshotComplete").getBoolean(p));
            p.onSnapshotEnd(); assertTrue(pipeline(p).usable());
            assertFalse(field("patternEventSeedFromSharedBook").getBoolean(p));
            time(p, 4000); p.onDepth(true, 5100, 0); time(p, 4500);
            assertTrue(snapshot(p).getAsJsonArray("patterns").isEmpty());
        });
    }
    @Test void observationsDoNotDispatchOrdersAndManualRoutingRemainsAvailable() throws Exception {
        Object previous = field("sharedServer").get(null);
        java.util.List<JsonObject> actions = new java.util.ArrayList<>();
        SignalWebSocketServer server = new SignalWebSocketServer(0,97);
        server.setTradingDispatch(actions::add);
        try {
            field("sharedServer").set(null,server);
            withIsolatedActivation(() -> {
                RongPlugin p = callbackPlugin(); time(p,0); p.onSnapshotEnd(); p.onBbo(5100,1,5121,1); withdrawal(p);
                assertFalse(snapshot(p).getAsJsonArray("patterns").isEmpty()); assertTrue(actions.isEmpty());
                JsonObject manual = new JsonObject(); manual.addProperty("symbol","TEST"); manual.addProperty("keyCode","KeyC");
                server.dispatchTradingAction(manual); assertEquals(1,actions.size());
            });
        } finally { field("sharedServer").set(null,previous); server.shutdown(); }
    }
}
