package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.controllers.WorkflowHandler;
import com.bookmap.plugin.rong.miniviteapp.models.Models.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowExecutionTest {
    static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    static JsonObject active() { return json("{\"hasValue\":true,\"stopLossPrice\":9,\"sizeMultipler\":0.1,\"plan\":{\"coreTarget\":13},\"submitEntryResult\":{\"tradeBookID\":\"RangeBoundBidReversal\",\"profitTargets\":[{\"target\":11,\"quantity\":40},{\"target\":12,\"quantity\":40}]}}"); }
    @Test void profitResetNeverRecreatesMoreSharesThanRemainingPosition() {
        JsonObject state = ExtendedExecutionPlanTest.longState(); state.addProperty("netQuantity", 50); state.getAsJsonObject("entryContext").add("activeTrade", active());
        Plan plan = WorkflowHandler.handle(new Snapshot(state), "KeyP", false); double shares = 0; boolean first = true;
        for (Request request : plan.requests) if (request.method.equals("POST")) { JsonArray children = request.body.getAsJsonArray("childOrderStrategies"); assertEquals("OCO", request.body.get("orderStrategyType").getAsString()); assertEquals(2, children.size());
            double quantity = children.get(0).getAsJsonObject().getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("quantity").getAsDouble(); assertEquals(quantity, children.get(1).getAsJsonObject().getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("quantity").getAsDouble()); shares += quantity;
            assertEquals(first ? 800 : 0, request.delayBeforeMs); if (first) assertEquals(12, children.get(1).getAsJsonObject().get("price").getAsDouble()); first = false;
        }
        assertEquals(50, shares);
        state.addProperty("netQuantity", 100); assertThrows(IllegalArgumentException.class, () -> WorkflowHandler.handle(new Snapshot(state), "KeyP", false));
    }
    @Test void qCancelsOnlyStopEntries() {
        JsonObject state = ExtendedExecutionPlanTest.longState(); state.add("entries", JsonParser.parseString("[{\"orderID\":\"101\",\"orderType\":\"STOP\",\"quantity\":10,\"price\":11,\"isBuy\":true},{\"orderID\":\"102\",\"orderType\":\"LIMIT\",\"quantity\":10,\"price\":9,\"isBuy\":true}]").getAsJsonArray());
        Plan plan = WorkflowHandler.handle(new Snapshot(state), "KeyQ", false); assertEquals(1, plan.requests.size()); assertEquals("101", plan.requests.get(0).orderId); assertEquals("DELETE", plan.requests.get(0).method); assertTrue(plan.clearPending);
    }
    @Test void pendingRefreshReplacesTriggerAndCapturesFreshLocalSizingWithoutPlanConfigs() {
        JsonObject state = ExtendedExecutionPlanTest.longState(); state.addProperty("netQuantity", 0); state.add("pairs", new JsonArray());
        state.add("entries", JsonParser.parseString("[{\"orderID\":\"101\",\"orderType\":\"STOP\",\"quantity\":10,\"price\":10.5,\"exitStopPrice\":9.5,\"isBuy\":true}]").getAsJsonArray());
        state.getAsJsonObject("entryContext").add("longTrade", active()); state.getAsJsonObject("entryContext").addProperty("lowOfDay", 9);
        Plan plan = WorkflowHandler.handle(new Snapshot(state), "RefreshEntryStop", false); assertEquals(1, plan.requests.size()); assertEquals("PUT", plan.requests.get(0).method); assertEquals("101", plan.requests.get(0).orderId); assertEquals("TRIGGER", plan.requests.get(0).body.get("orderStrategyType").getAsString());
        assertEquals(1, plan.entry.getAsJsonObject("basePlan").getAsJsonObject("planConfigs").get("sizingCount").getAsInt()); assertEquals(9, plan.entry.get("stopOutPrice").getAsDouble()); assertFalse(plan.requireFlatEntry);
    }
    @Test void trailQuoteClampCannotBypassRestrictedCorePartial() {
        JsonObject state = ExtendedExecutionPlanTest.longState(); state.addProperty("coreRuleEnabled", true); state.addProperty("hasPlan", true); state.addProperty("coreCount", 10); state.addProperty("coreTarget", 15); state.addProperty("entryPrice", 10);
        JsonArray candles = new JsonArray(); for (int minute = 0; minute <= 5; minute++) { JsonObject candle = json("{\"high\":12,\"low\":11}"); candle.addProperty("datetime", 1790861400000L + minute * 60000); candles.add(candle); }
        state.getAsJsonObject("entryContext").add("candles", candles); state.addProperty("bid", 10.5);
        state.getAsJsonArray("pairs").get(0).getAsJsonObject().addProperty("originalPartial", 10);
        assertThrows(IllegalArgumentException.class, () -> WorkflowHandler.handle(new Snapshot(state), "KeyJ", false));
    }
}
