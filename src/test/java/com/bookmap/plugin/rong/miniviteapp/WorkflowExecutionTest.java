package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.controllers.WorkflowHandler;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.KeyboardHandler;
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
    @Test void profitResetLeavesTargetStopRelationshipToBrokerLikeViteApp() {
        JsonObject state = ExtendedExecutionPlanTest.longState(); state.addProperty("netQuantity", 50);
        JsonObject trade = active(); trade.addProperty("stopLossPrice", 13);
        state.getAsJsonObject("entryContext").add("activeTrade", trade);
        Plan plan = WorkflowHandler.handle(new Snapshot(state), "KeyP", false);
        var order = plan.requests.stream().filter(request -> request.method.equals("POST")).findFirst().orElseThrow().body;
        var children = order.getAsJsonArray("childOrderStrategies");
        assertEquals(13, children.get(0).getAsJsonObject().get("stopPrice").getAsDouble());
        assertEquals(12, children.get(1).getAsJsonObject().get("price").getAsDouble());
    }
    @Test void cCancelsOnlyStopEntries() {
        JsonObject state = ExtendedExecutionPlanTest.longState(); state.add("entries", JsonParser.parseString("[{\"orderID\":\"101\",\"orderType\":\"STOP\",\"quantity\":10,\"price\":11,\"isBuy\":true},{\"orderID\":\"102\",\"orderType\":\"LIMIT\",\"quantity\":10,\"price\":9,\"isBuy\":true}]").getAsJsonArray());
        for (boolean shift : new boolean[]{false, true}) {
            Plan plan = KeyboardHandler.handleKeyPressed(new Snapshot(state), "KeyC", shift, Double.NaN); assertEquals(1, plan.requests.size()); assertEquals("101", plan.requests.get(0).orderId); assertEquals("DELETE", plan.requests.get(0).method); assertTrue(plan.clearPending);
            assertFalse(KeyboardHandler.supports("KeyQ", shift));
        }
    }
    @Test void pendingRefreshReplacesTriggerAndCapturesFreshLocalSizingWithoutPlanConfigs() {
        JsonObject state = ExtendedExecutionPlanTest.longState(); state.addProperty("netQuantity", 0); state.add("pairs", new JsonArray());
        state.add("entries", JsonParser.parseString("[{\"orderID\":\"101\",\"orderType\":\"STOP\",\"quantity\":10,\"price\":10.5,\"exitStopPrice\":9.5,\"isBuy\":true}]").getAsJsonArray());
        state.getAsJsonObject("entryContext").add("longTrade", active()); state.getAsJsonObject("entryContext").addProperty("lowOfDay", 9);
        Plan plan = WorkflowHandler.handle(new Snapshot(state), "RefreshEntryStop", false); assertEquals(1, plan.requests.size()); assertEquals("PUT", plan.requests.get(0).method); assertEquals("101", plan.requests.get(0).orderId); assertEquals("TRIGGER", plan.requests.get(0).body.get("orderStrategyType").getAsString());
        assertEquals(10, plan.entry.getAsJsonObject("basePlan").getAsJsonObject("planConfigs").get("sizingCount").getAsInt()); assertEquals(9, plan.entry.get("stopOutPrice").getAsDouble());
        state.getAsJsonObject("entryContext").getAsJsonObject("longTrade").getAsJsonObject("plan").add("planConfigs", json("{\"sizingCount\":1}"));
        state.getAsJsonObject("entryContext").getAsJsonObject("longTrade").getAsJsonObject("plan").addProperty("entryMethod", "0.1 R");
        plan = WorkflowHandler.handle(new Snapshot(state), "RefreshEntryStop", false);
        assertEquals(1, plan.entry.getAsJsonObject("basePlan").getAsJsonObject("planConfigs").get("sizingCount").getAsInt());
        assertEquals(1, plan.requests.get(0).body.getAsJsonArray("childOrderStrategies").size());
    }
    @Test void removedTrailingKeysCannotFallThroughToAnotherExitWorkflow() {
        Snapshot state = new Snapshot(ExtendedExecutionPlanTest.longState());
        for (String key : new String[]{"KeyJ", "KeyK", "KeyL"}) for (boolean shift : new boolean[]{false, true}) {
            assertFalse(WorkflowHandler.supports(key));
            assertFalse(KeyboardHandler.supports(key, shift));
            assertEquals("unsupported native workflow: " + key,
                    assertThrows(IllegalArgumentException.class, () -> WorkflowHandler.handle(state, key, shift)).getMessage());
            assertEquals("unsupported native action: " + key,
                    assertThrows(IllegalArgumentException.class, () -> KeyboardHandler.handleKeyPressed(state, key, shift, 11)).getMessage());
        }
    }
}
