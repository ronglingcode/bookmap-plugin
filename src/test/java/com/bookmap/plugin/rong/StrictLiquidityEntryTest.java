package com.bookmap.plugin.rong;

import com.bookmap.plugin.rong.miniviteapp.core.controllers.BookmapSizeCap;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.BookmapSizeCap.Mode;
import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.OrderFactory;
import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import com.bookmap.plugin.rong.miniviteapp.models.Models.*;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StrictLiquidityEntryTest {
    private static final long OPEN = LocalDateTime.parse("2026-10-16T09:30:00")
            .atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli() * 1_000_000L;
    private SignalWebSocketServer server() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 0);
        server.registerSymbol("TEST", new OrderBookState(), .01);
        server.observeSessionTime("TEST", OPEN);
        server.updateRegularSessionHighLow("TEST", 99, OPEN);
        server.updateRegularSessionHighLow("TEST", 101, OPEN);
        server.observeSessionDepth("TEST", true, 10000, 1000, OPEN);
        server.observeSessionDepth("TEST", false, 10200, 6000, OPEN);
        server.observeSessionDepth("TEST", false, 10400, 10000, OPEN);
        server.observeSessionDepth("TEST", true, 9800, 7000, OPEN);
        server.observeSessionDepth("TEST", true, 9600, 12000, OPEN);
        server.observeSessionTrade("TEST", 10000, 1000, false, OPEN, true);
        return server;
    }
    private JsonObject state(JsonObject cap) {
        JsonObject state = JsonParser.parseReader(new InputStreamReader(getClass().getResourceAsStream("/direct-entry-fixtures.json"), StandardCharsets.UTF_8))
                .getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("state").deepCopy();
        state.addProperty("symbol", "TEST"); state.addProperty("averagePrice", 100);
        state.getAsJsonObject("entryContext").add("bookmapSizeCap", cap);
        state.getAsJsonObject("entryContext").addProperty("riskDollars", 1000);
        return state;
    }
    private JsonObject strictCap(SignalWebSocketServer server) {
        JsonObject cap = server.entrySizeCap("TEST");
        cap.addProperty("mode", Mode.STRICT.name());
        return cap;
    }
    private Plan entry(boolean isLong, String type, double trigger) {
        Plan plan = new Plan("wall_reversal_entry");
        plan.requests.add(new Request("POST", null, OrderFactory.createOneEntryWithTwoExits("TEST", isLong, type, 100, trigger,
                isLong ? 110 : 90, isLong ? 95 : 105)));
        return plan;
    }
    private double quantity(Plan plan) {
        return plan.requests.get(0).body.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("quantity").getAsDouble();
    }
    @Test void eightMillionAndHigherPremarketVolumesRequireExactlyFortyThousandFilledShares() {
        for (double volume : new double[]{8000000, 10709318, 100000000}) {
            long chartTime = OPEN / 1_000_000L;
            try (PremarketSizeThreshold policy = new PremarketSizeThreshold("TEST", () -> chartTime, () -> chartTime,
                    (symbol, start, end) -> List.of(new Candle(symbol, start, 100, 100, 100, 100, volume, 100)))) {
                policy.refresh();
                SignalWebSocketServer server = server();
                try {
                    server.registerSizeThreshold("TEST", policy);
                    assertEquals(40000, server.entrySizeCap("TEST").get("requiredOrderSize").getAsInt());
                    server.observeSessionDepth("TEST", false, 10200, 40000, OPEN);
                    server.observeSessionTrade("TEST", 10200, 39999, true, OPEN + 1, true);
                    for (boolean isLong : new boolean[]{true, false})
                        for (String type : new String[]{"MARKET", "LIMIT", "STOP", "STOP_LIMIT"})
                            assertThrows(IllegalArgumentException.class, () -> BookmapSizeCap.apply(
                                    new Snapshot(state(strictCap(server))), entry(isLong, type, 100)));
                    server.observeSessionTrade("TEST", 10200, 1, true, OPEN + 2, true);
                    for (boolean isLong : new boolean[]{true, false})
                        for (String type : new String[]{"MARKET", "LIMIT", "STOP", "STOP_LIMIT"}) {
                            Plan plan = entry(isLong, type, 100);
                            JsonObject original = plan.requests.get(0).body.deepCopy();
                            BookmapSizeCap.apply(new Snapshot(state(strictCap(server))), plan);
                            assertEquals(original, plan.requests.get(0).body);
                        }
                } finally { server.shutdown(); }
            }
        }
    }
    @Test void historicalFillQualifiesLateAttachmentOnceHistoryCatchesUp() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 0);
        server.registerSymbol("TEST", new OrderBookState(), .01);
        server.beginSessionHistory("TEST");
        server.observeSessionHistoricalTrade("TEST", 10000, 5000, true, OPEN);
        assertThrows(IllegalArgumentException.class, () -> BookmapSizeCap.apply(
                new Snapshot(state(server.entrySizeCap("TEST"))), entry(true, "LIMIT", 100)));
        server.finishSessionHistory("TEST");
        for (boolean isLong : new boolean[]{true, false})
            for (String type : new String[]{"MARKET", "LIMIT", "STOP", "STOP_LIMIT"}) {
                Plan plan = entry(isLong, type, 100);
                JsonObject original = plan.requests.get(0).body.deepCopy();
                BookmapSizeCap.apply(new Snapshot(state(server.entrySizeCap("TEST"))), plan);
                assertEquals(original, plan.requests.get(0).body);
            }
    }
    @Test void strictIsDefaultAndBlocksEvenTheSmallestRequestsWithoutQualifyingLiquidity() {
        var server = server(); JsonObject cap = server.entrySizeCap("TEST");
        assertEquals(Mode.STRICT, BookmapSizeCap.ACTIVE_MODE);
        assertEquals(Mode.STRICT, BookmapSizeCap.mode(new JsonObject()));
        assertEquals("STRICT", cap.get("mode").getAsString());
        for (String type : new String[]{"MARKET", "LIMIT"}) for (boolean isLong : new boolean[]{true, false}) {
            Plan plan = entry(isLong, type, isLong ? 104 : 96);
            assertTrue(assertThrows(IllegalArgumentException.class, () -> BookmapSizeCap.apply(new Snapshot(state(cap)), plan))
                    .getMessage().contains("STRICT: ENTRY BLOCKED"));
        }
        assertThrows(IllegalArgumentException.class, () -> BookmapSizeCap.apply(new Snapshot(state(cap)), entry(true, "STOP", 101.99)));
        assertThrows(IllegalArgumentException.class, () -> BookmapSizeCap.apply(new Snapshot(state(cap)), entry(false, "STOP", 98.01)));
    }
    @Test void unfilledOutsideWallsCannotQualifyAnyOrderEvenWithOutwardStopTriggers() {
        var server = server();
        server.observeSessionDepth("TEST", false, 10200, 0, OPEN + 1);
        server.updateRegularSessionHighLow("TEST", 104, OPEN + 2);
        for (boolean isLong : new boolean[]{true, false}) for (String type : new String[]{"MARKET", "LIMIT", "STOP", "STOP_LIMIT"})
            assertThrows(IllegalArgumentException.class, () -> BookmapSizeCap.apply(
                    new Snapshot(state(strictCap(server))), entry(isLong, type, isLong ? 104 : 96)));
    }
    @Test void qualifyingExecutionsUnlockEveryEntryTypeWithoutProspectiveRangeExceptions() {
        var server = server();
        server.observeSessionTrade("TEST", 10000, 4000, false, OPEN + 1, true);
        for (boolean isLong : new boolean[]{true, false}) for (String type : new String[]{"MARKET", "LIMIT", "STOP", "STOP_LIMIT"}) {
            Plan plan = entry(isLong, type, isLong ? 104 : 96);
            JsonObject original = plan.requests.get(0).body.deepCopy();
            BookmapSizeCap.apply(new Snapshot(state(strictCap(server))), plan);
            assertEquals(original, plan.requests.get(0).body);
        }
        server.observeSessionTime("TEST", OPEN);
        assertThrows(IllegalArgumentException.class, () -> BookmapSizeCap.apply(new Snapshot(state(strictCap(server))), entry(true, "STOP", 104)));
    }
    @Test void strictThresholdEqualityUnlocksNormalSizeAndAddsWithoutProportionalReduction() {
        var server = server(); server.observeSessionTrade("TEST", 10000, 4000, false, OPEN, true);
        JsonObject json = state(strictCap(server)); json.addProperty("netQuantity", 500);
        json.getAsJsonObject("entryContext").addProperty("lowOfDay", 99);
        for (String action : new String[]{"wall_reversal_entry", "reload_partial", "swap", "refresh_pending_entry"}) {
            Plan plan = entry(true, "LIMIT", 100);
            Plan named = new Plan(action); named.requests.addAll(plan.requests);
            BookmapSizeCap.apply(new Snapshot(json), named); assertEquals(100, quantity(named));
        }
    }
    @Test void proportionalAllowsEntriesBelowStrictThreshold() {
        var server = server(); JsonObject cap = server.entrySizeCap("TEST").deepCopy();
        cap.addProperty("mode", Mode.PROPORTIONAL.name());
        assertEquals(Mode.PROPORTIONAL, BookmapSizeCap.mode(cap));
        Plan normal = entry(true, "LIMIT", 100);
        BookmapSizeCap.apply(new Snapshot(state(cap)), normal); assertEquals(40, quantity(normal));
        Plan stop = entry(true, "STOP", 102);
        BookmapSizeCap.apply(new Snapshot(state(cap)), stop); assertEquals(28, quantity(stop)); // 0.2R / $7 per share.
        assertThrows(IllegalArgumentException.class, () -> BookmapSizeCap.apply(new Snapshot(state(strictCap(server))), entry(true, "LIMIT", 100)));
    }
    @Test void normalOneRiskIgnoresMissingFillHistoryWhenSelected() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 0);
        JsonObject cap = server.entrySizeCap("TEST");
        cap.addProperty("mode", Mode.NORMAL_1R.name());
        assertEquals(Mode.NORMAL_1R, BookmapSizeCap.mode(cap));
        assertFalse(cap.get("available").getAsBoolean());
        assertEquals(0, cap.get("largestOrderSize").getAsInt());
        JsonObject json = state(cap);
        json.addProperty("currentPrice", 100);
        json.addProperty("bid", 99.99);
        json.addProperty("ask", 100.01);
        for (boolean isLong : new boolean[]{true, false})
            for (String type : new String[]{"MARKET", "LIMIT", "STOP", "STOP_LIMIT"}) {
                Plan plan = entry(isLong, type, 100);
                JsonObject original = plan.requests.get(0).body.deepCopy();
                BookmapSizeCap.apply(new Snapshot(json), plan);
                assertEquals(original, plan.requests.get(0).body);
            }
    }
    @Test void strictCannotBypassUnavailableSessionOrBorrowAnotherStopsWiderRangeAndNeverBlocksExits() {
        var server = server(); JsonObject cap = strictCap(server);
        Plan plan = entry(true, "STOP", 102); plan.requests.addAll(entry(true, "STOP", 101.99).requests);
        assertThrows(IllegalArgumentException.class, () -> BookmapSizeCap.apply(new Snapshot(state(cap)), plan));
        cap.addProperty("available", false);
        assertThrows(IllegalArgumentException.class, () -> BookmapSizeCap.apply(new Snapshot(state(cap)), entry(true, "STOP", 104)));
        Plan exit = new Plan("flatten"); exit.requests.add(new Request("POST", null, OrderFactory.createSingleOrder("TEST", "MARKET", 100, 0, false)));
        assertDoesNotThrow(() -> BookmapSizeCap.apply(new Snapshot(state(cap)), exit));
    }
}
