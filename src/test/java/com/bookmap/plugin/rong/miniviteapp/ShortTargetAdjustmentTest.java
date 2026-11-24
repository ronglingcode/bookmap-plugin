package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.controllers.KeyboardHandler;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.NativeViews;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.WorkflowHandler;
import com.bookmap.plugin.rong.miniviteapp.models.Models.Snapshot;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShortTargetAdjustmentTest {
    private JsonObject shortState() {
        JsonObject state = JsonParser.parseReader(new InputStreamReader(getClass().getResourceAsStream(
                "/direct-execution-fixtures.json"), StandardCharsets.UTF_8)).getAsJsonArray().get(0)
                .getAsJsonObject().getAsJsonObject("state").deepCopy();
        state.addProperty("netQuantity", -100); state.addProperty("coreRuleEnabled", false);
        state.addProperty("currentPrice", 10); state.addProperty("bid", 9.99); state.addProperty("ask", 10.01);
        JsonObject template = state.getAsJsonArray("pairs").get(0).getAsJsonObject().deepCopy();
        JsonArray pairs = new JsonArray();
        for (int i = 1; i <= 10; i++) {
            JsonObject pair = template.deepCopy(); pair.addProperty("originalPartial", i);
            for (String leg : new String[]{"STOP", "LIMIT"}) {
                JsonObject order = pair.getAsJsonObject(leg); order.addProperty("isBuy", true);
                order.addProperty("price", leg.equals("STOP") ? 11 : 9 - i * .01);
                order.addProperty("orderID", i + leg); order.addProperty("quantity", 10);
            }
            pairs.add(pair);
        }
        state.add("pairs", pairs);
        state.add("entryContext", JsonParser.parseString("{\"activeTrade\":{}}").getAsJsonObject());
        return state;
    }

    @Test void allShortTargetsAndBatchAdjustmentsWorkWithoutAMarker() {
        JsonObject state = shortState();
        for (String key : new String[]{"Digit1", "Digit2", "Digit3", "Digit4", "Digit5", "Digit0", "KeyG", "KeyH", "KeyT"}) {
            assertFalse(KeyboardHandler.handleKeyPressed(new Snapshot(state), key, false, 9.5).requests.isEmpty(), key);
            assertFalse(KeyboardHandler.handleKeyPressed(new Snapshot(state), key, false, 10.5).requests.isEmpty(), "Stop adjustment: " + key);
        }
        for (int i = 0; i < 3; i++) state.getAsJsonArray("pairs").remove(0);
        assertEquals(1, KeyboardHandler.handleKeyPressed(new Snapshot(state), "Digit1", false, 9.5).requests.size());
    }

    @Test void staleMarkerCannotOverrideCoreTargetProtection() {
        JsonObject state = shortState();
        state.addProperty("coreRuleEnabled", true); state.addProperty("hasPlan", true);
        state.addProperty("entryPrice", 10); state.addProperty("coreTarget", 8); state.addProperty("coreCount", 7);
        state.getAsJsonObject("entryContext").getAsJsonObject("activeTrade").addProperty("entryBidToReclaim", 10.1);
        assertThrows(IllegalArgumentException.class,
                () -> KeyboardHandler.handleKeyPressed(new Snapshot(state), "Digit0", false, 9.5));
        assertEquals(1, KeyboardHandler.handleKeyPressed(new Snapshot(state), "Digit0", false, 8).requests.size());
    }

    @Test void resetTargetsWorksWithoutAMarker() {
        JsonObject state = shortState();
        state.getAsJsonObject("entryContext").addProperty("lowOfDay", 9);
        state.getAsJsonObject("entryContext").addProperty("highOfDay", 11);
        assertFalse(WorkflowHandler.handle(new Snapshot(state), "KeyP", false).requests.isEmpty());
    }

    @Test void nativeDisplayIgnoresSavedManualEntryBid() {
        JsonObject view = JsonParser.parseString("{\"type\":\"command_state\",\"symbol\":\"AAPL\",\"account\":{\"positions\":{\"AAPL\":{\"netQuantity\":-100,\"averagePrice\":10}}},\"state\":{\"stateBySymbol\":{\"AAPL\":{\"breakoutTradeStateForShort\":{\"entryBidToReclaim\":9.9}}}}}").getAsJsonObject();
        assertTrue(NativeViews.project(view).stream().noneMatch(message -> message.get("type").getAsString()
                .equals("entry_bid_reclaim_config")));
    }
}
