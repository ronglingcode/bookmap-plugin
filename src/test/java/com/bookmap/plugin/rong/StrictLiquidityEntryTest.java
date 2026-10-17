package com.bookmap.plugin.rong;

import com.bookmap.plugin.rong.miniviteapp.core.controllers.BookmapSizeCap;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.BookmapSizeCap.Mode;
import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.OrderFactory;
import com.bookmap.plugin.rong.miniviteapp.models.Models.*;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.*;
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
    private Plan entry(boolean isLong, String type, double trigger) {
        Plan plan = new Plan("wall_reversal_entry");
        plan.requests.add(new Request("POST", null, OrderFactory.createOneEntryWithTwoExits("TEST", isLong, type, 100, trigger,
                isLong ? 110 : 90, isLong ? 95 : 105)));
        return plan;
    }
    private double quantity(Plan plan) {
        return plan.requests.get(0).body.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("quantity").getAsDouble();
    }
    @Test void strictIsDefaultAndBlocksEvenTheSmallestRequestsWithoutQualifyingLiquidity() {
        var server = server(); JsonObject cap = server.entrySizeCap("TEST");
        assertEquals(Mode.STRICT, BookmapSizeCap.ACTIVE_MODE);
        assertEquals("STRICT", cap.get("mode").getAsString());
        for (String type : new String[]{"MARKET", "LIMIT"}) for (boolean isLong : new boolean[]{true, false}) {
            Plan plan = entry(isLong, type, isLong ? 104 : 96);
            assertTrue(assertThrows(IllegalArgumentException.class, () -> BookmapSizeCap.apply(new Snapshot(state(cap)), plan))
                    .getMessage().contains("STRICT: ENTRY BLOCKED"));
        }
        assertThrows(IllegalArgumentException.class, () -> BookmapSizeCap.apply(new Snapshot(state(cap)), entry(true, "STOP", 101.99)));
        assertThrows(IllegalArgumentException.class, () -> BookmapSizeCap.apply(new Snapshot(state(cap)), entry(false, "STOP", 98.01)));
    }
    @Test void buyAndSellStopsQualifyOnlyThroughTheirOwnTriggerIncludingEquality() {
        var server = server(); JsonObject cap = server.entrySizeCap("TEST");
        for (boolean isLong : new boolean[]{true, false}) {
            double trigger = isLong ? 102 : 98;
            Plan plan = entry(isLong, "STOP", trigger); JsonObject original = plan.requests.get(0).body.deepCopy();
            BookmapSizeCap.apply(new Snapshot(state(cap)), plan);
            assertEquals(original, plan.requests.get(0).body);
            assertEquals(isLong ? 6000 : 7000, BookmapSizeCap.forEntry(cap, plan.requests.get(0).body).get("largestOrderSize").getAsInt());
        }
        assertEquals(1000, cap.get("largestOrderSize").getAsInt()); // Hypothetical ranges never unlock other entries.
        assertEquals(101, server.getRegularSessionHighLow("TEST").getHighOfDay());
        assertEquals(99, server.getRegularSessionHighLow("TEST").getLowOfDay());
        assertThrows(IllegalArgumentException.class, () -> BookmapSizeCap.apply(new Snapshot(state(cap)), entry(true, "STOP", 98)));
        assertThrows(IllegalArgumentException.class, () -> BookmapSizeCap.apply(new Snapshot(state(cap)), entry(false, "STOP", 102)));
    }
    @Test void cancelledOutsideOrdersStillQualifyStopsButReplayedFutureOrdersDoNot() {
        var server = server(); server.observeSessionDepth("TEST", false, 10200, 0, OPEN + 1);
        BookmapSizeCap.apply(new Snapshot(state(server.entrySizeCap("TEST"))), entry(true, "STOP", 102));
        server.observeSessionTime("TEST", OPEN - 1);
        server.observeSessionTime("TEST", OPEN);
        server.updateRegularSessionHighLow("TEST", 100, OPEN);
        assertThrows(IllegalArgumentException.class, () -> BookmapSizeCap.apply(new Snapshot(state(server.entrySizeCap("TEST"))), entry(true, "STOP", 102)));
    }
    @Test void strictThresholdEqualityUnlocksNormalSizeAndAddsWithoutProportionalReduction() {
        var server = server(); server.observeSessionDepth("TEST", true, 10000, 5000, OPEN);
        JsonObject json = state(server.entrySizeCap("TEST")); json.addProperty("netQuantity", 500);
        json.getAsJsonObject("entryContext").addProperty("lowOfDay", 99);
        for (String action : new String[]{"wall_reversal_entry", "reload_partial", "swap", "refresh_pending_entry"}) {
            Plan plan = entry(true, "LIMIT", 100);
            Plan named = new Plan(action); named.requests.addAll(plan.requests);
            BookmapSizeCap.apply(new Snapshot(json), named); assertEquals(100, quantity(named));
        }
    }
    @Test void proportionalModeRemainsAvailableAndUsesTheSameProspectiveStopRange() {
        var server = server(); JsonObject cap = server.entrySizeCap("TEST").deepCopy();
        cap.addProperty("mode", Mode.PROPORTIONAL.name()); // Exercise the retained algorithm without exposing a runtime switch.
        Plan normal = entry(true, "LIMIT", 100);
        BookmapSizeCap.apply(new Snapshot(state(cap)), normal); assertEquals(40, quantity(normal));
        for (boolean isLong : new boolean[]{true, false}) {
            Plan stop = entry(isLong, "STOP", isLong ? 102 : 98);
            BookmapSizeCap.apply(new Snapshot(state(cap)), stop); assertEquals(100, quantity(stop));
        }
        assertThrows(IllegalArgumentException.class, () -> BookmapSizeCap.apply(new Snapshot(state(server.entrySizeCap("TEST"))), entry(true, "LIMIT", 100)));
    }
    @Test void strictCannotBypassUnavailableSessionOrBorrowAnotherStopsWiderRangeAndNeverBlocksExits() {
        var server = server(); JsonObject cap = server.entrySizeCap("TEST");
        Plan plan = entry(true, "STOP", 102); plan.requests.addAll(entry(true, "STOP", 101.99).requests);
        assertThrows(IllegalArgumentException.class, () -> BookmapSizeCap.apply(new Snapshot(state(cap)), plan));
        cap.addProperty("available", false);
        assertThrows(IllegalArgumentException.class, () -> BookmapSizeCap.apply(new Snapshot(state(cap)), entry(true, "STOP", 104)));
        Plan exit = new Plan("flatten"); exit.requests.add(new Request("POST", null, OrderFactory.createSingleOrder("TEST", "MARKET", 100, 0, false)));
        assertDoesNotThrow(() -> BookmapSizeCap.apply(new Snapshot(state(cap)), exit));
    }
}
