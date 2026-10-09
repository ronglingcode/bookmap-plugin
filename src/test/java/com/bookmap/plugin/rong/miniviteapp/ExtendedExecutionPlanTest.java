package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.controllers.ExtendedHandler;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.EntryHandler;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.ExtendedEntryRules;
import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.bookmap.plugin.rong.miniviteapp.models.Models.Snapshot;
import com.bookmap.plugin.rong.miniviteapp.models.Models.Plan;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedExecutionPlanTest {
    static JsonArray fixtures() {
        return JsonParser.parseReader(new InputStreamReader(ExtendedExecutionPlanTest.class
                .getResourceAsStream("/extended-execution-fixtures.json"), StandardCharsets.UTF_8)).getAsJsonArray();
    }
    static JsonObject longState() { return fixtures().get(0).getAsJsonObject().getAsJsonObject("state").deepCopy(); }
    @Test void swapReentryUsesSuppliedLiveWalls() {
        JsonObject action = JsonParser.parseString("{\"orderbook\":{\"effectiveWallThreshold\":5000,\"largeAsks\":[[10.75,6000]]}}").getAsJsonObject();
        Plan plan = ExtendedHandler.swap(new Snapshot(longState()), action);
        JsonArray targets = plan.entry.getAsJsonObject("submitEntryResult").getAsJsonArray("profitTargets");
        assertTrue(java.util.stream.StreamSupport.stream(targets.spliterator(), false)
                .anyMatch(target -> target.getAsJsonObject().get("target").getAsDouble() == 10.75));
    }
    @Test void reloadAndSwapMatchNativePolicyFixtures() {
        for (var element : fixtures()) {
            var fixture = element.getAsJsonObject(); var state = new Snapshot(fixture.getAsJsonObject("state"));
            var action = fixture.getAsJsonObject("action"); String name = Models.string(fixture, "name");
            java.util.function.Supplier<Plan> plan = () -> Models.string(action, "keyCode").equals("KeyA")
                    ? ExtendedHandler.reload(state, Models.bool(action, "shiftKey"), Models.number(action, "price")) : ExtendedHandler.swap(state);
            if (Models.bool(fixture, "error")) assertThrows(IllegalArgumentException.class, plan::get, name);
            else assertEquals(fixture.getAsJsonArray("requests"), plan.get().toJson(), name);
        }
    }
    @Test void sameDirectionEntryKeepsTradeStateAndCancelsOldEntriesAfterOpening() {
        JsonObject json = longState();
        json.add("entries", JsonParser.parseString("[{\"orderID\":\"101\",\"isBuy\":true,\"orderType\":\"STOP\",\"quantity\":100,\"price\":10.5}]").getAsJsonArray());
        JsonObject action = new JsonObject(); action.addProperty("tradebook_id", "RangeBoundBidReversal");
        Plan plan = EntryHandler.handleEntry(new Snapshot(json), action, "", true);
        assertEquals("POST", plan.requests.get(0).method);
        assertEquals("DELETE", plan.requests.get(1).method);
        assertEquals("101", plan.requests.get(1).orderId);
        assertTrue(plan.entry.get("preserveExistingTrade").getAsBoolean());
    }
    @Test void oppositeMarketEntryClosesOldExitsBeforeProtectedOpening() {
        JsonObject json = longState();
        JsonObject action = new JsonObject(); action.addProperty("tradebook_id", "RangeBoundOfferReversal"); action.addProperty("use_market_order", true);
        Plan plan = EntryHandler.handleEntry(new Snapshot(json), action, "", true);
        assertEquals(5, plan.requests.size());
        for (int i = 0; i < 4; i++) assertEquals("PUT", plan.requests.get(i).method);
        assertEquals("POST", plan.requests.get(4).method);
        assertFalse(plan.entry.get("preserveExistingTrade").getAsBoolean());
    }
    @Test void oppositeStopEntryMovesOldProtectiveStopsBeforeOpening() {
        JsonObject json = longState();
        JsonObject action = new JsonObject(); action.addProperty("tradebook_id", "RangeBoundOfferReversal");
        Plan plan = EntryHandler.handleEntry(new Snapshot(json), action, "", true);
        assertEquals("202", plan.requests.get(0).orderId);
        assertEquals("STOP", Models.string(plan.requests.get(0).body, "orderType"));
        assertEquals(9.5, Models.number(plan.requests.get(0).body, "stopPrice"));
        assertEquals("POST", plan.requests.get(4).method);
    }
    @Test void hoverBsEntriesStayOneCentBeyondOppositeStopsIncludingQuoteAdjustments() {
        for (boolean isLong : new boolean[] { false, true }) {
            for (boolean adjusted : new boolean[] { false, true }) {
                JsonObject json = longState();
                json.addProperty("netQuantity", isLong ? -1000 : 1000);
                json.addProperty("currentPrice", isLong ? 10 : 12);
                double protectivePrice = adjusted ? (isLong ? 11.01 : 10.99) : 11;
                json.addProperty("bid", adjusted ? protectivePrice : (isLong ? 10 : 12));
                json.addProperty("ask", adjusted ? protectivePrice : (isLong ? 10 : 12));
                json.getAsJsonObject("entryContext").addProperty("customStopShort", 13);
                for (var element : json.getAsJsonArray("pairs")) {
                    element.getAsJsonObject().getAsJsonObject("STOP").addProperty("isBuy", isLong);
                    element.getAsJsonObject().getAsJsonObject("LIMIT").addProperty("isBuy", isLong);
                }
                JsonObject action = new JsonObject();
                action.addProperty("source", "bookmap_chart_hotkey");
                action.addProperty("tradebook_id", isLong ? "RangeBoundBidReversal" : "RangeBoundOfferReversal");
                action.addProperty("price", 11);
                action.addProperty("shiftKey", true); // Hover B/S must still submit stop entries.
                String key = isLong ? "KeyB" : "KeyS";
                assertTrue(EntryHandler.supports(action, key));
                Plan plan = EntryHandler.handleEntry(new Snapshot(json), action, key, true);
                assertEquals(5, plan.requests.size());
                for (int i = 0; i < 4; i++) {
                    assertEquals("PUT", plan.requests.get(i).method);
                    assertEquals(protectivePrice, Models.number(plan.requests.get(i).body, "stopPrice"));
                }
                var opening = plan.requests.get(4);
                assertEquals("POST", opening.method);
                assertEquals("STOP", Models.string(opening.body, "orderType"));
                assertEquals(protectivePrice + (isLong ? 0.01 : -0.01), Models.number(opening.body, "stopPrice"), 0.000001);
                assertEquals(isLong ? "BUY" : "SELL_SHORT", Models.string(opening.body.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject(), "instruction"));
            }
        }
    }
    @Test void existingRiskReducesEntryBeforeMethodAndLiquidityMultipliers() {
        JsonObject json = longState();
        json.addProperty("averagePrice", 10.6); // 1100 risk: only 0.1R remains below the 1.2R cap.
        assertEquals(0.1, ExtendedEntryRules.nextEntryMultiplier(new Snapshot(json), true, 10));
        json.addProperty("averagePrice", 10.71);
        assertThrows(IllegalArgumentException.class, () -> ExtendedEntryRules.nextEntryMultiplier(new Snapshot(json), true, 10));
        json.getAsJsonObject("entryContext").addProperty("addTargetLong", 9.9);
        assertEquals(1, ExtendedEntryRules.nextEntryMultiplier(new Snapshot(json), true, 10));
    }
    @Test void pendingEntryWithoutPositionCanBeReplacedAndGenericBsSelectsExactlyOneTradebook() {
        JsonObject json = longState(); json.addProperty("netQuantity", 0); json.add("pairs", new JsonArray());
        json.add("entries", JsonParser.parseString("[{\"orderID\":\"101\",\"isBuy\":true,\"orderType\":\"STOP\",\"quantity\":100,\"price\":10.5}]").getAsJsonArray());
        JsonObject action = new JsonObject(); action.addProperty("shiftKey", true);
        Plan plan = EntryHandler.handleDirectionalEntry(new Snapshot(json), action, "KeyB");
        assertEquals("MARKET", Models.string(plan.requests.get(0).body, "orderType"));
        assertEquals("DELETE", plan.requests.get(1).method);
        JsonArray definitions = json.getAsJsonObject("entryContext").getAsJsonArray("definitions");
        definitions.add(definitions.get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> EntryHandler.handleDirectionalEntry(new Snapshot(json), action, "KeyB"));
    }
    @Test void nonDefaultRiskMethodMatchesViteParsingAndFlatEntryStillGetsExposurePreflight() {
        JsonObject json = longState(); json.addProperty("netQuantity", 0); json.add("pairs", new JsonArray());
        JsonObject action = new JsonObject(); action.addProperty("tradebook_id", "RangeBoundBidReversal"); action.addProperty("entry_method", "pattern 0.25R");
        Plan plan = EntryHandler.handleEntry(new Snapshot(json), action, "");
        assertEquals(0.25, Models.number(plan.entry, "multiplier"));
        assertEquals(10, plan.requests.get(0).body.getAsJsonArray("childOrderStrategies").size());
    }
}
