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

class EntryBidReclaimTest {
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

    @Test void firstThreeAreFreeLaterTargetsRequireTheMarkerIncludingBatchAdjustments() {
        JsonObject state = shortState();
        for (String key : new String[]{"Digit1", "Digit2", "Digit3"})
            assertFalse(KeyboardHandler.handleKeyPressed(new Snapshot(state), key, false, 9.5).requests.isEmpty());
        for (String key : new String[]{"Digit4", "Digit5", "Digit0", "KeyG", "KeyH", "KeyT"}) {
            IllegalArgumentException blocked = assertThrows(IllegalArgumentException.class,
                    () -> KeyboardHandler.handleKeyPressed(new Snapshot(state), key, false, 9.5));
            assertTrue(blocked.getMessage().contains("Mark entry bid"));
            assertFalse(KeyboardHandler.handleKeyPressed(new Snapshot(state), key, false, 10.5).requests.isEmpty(), "Stop adjustments stay available");
        }
        for (String key : new String[]{"KeyM", "Numpad4", "KeyF"})
            assertFalse(KeyboardHandler.handleKeyPressed(new Snapshot(state), key, false, 0).requests.isEmpty());
        state.getAsJsonObject("entryContext").getAsJsonObject("activeTrade").addProperty("entryBidToReclaim", 10.1);
        assertEquals(10, KeyboardHandler.handleKeyPressed(new Snapshot(state), "KeyT", false, 9.5).requests.size());
    }

    @Test void remainingPartialFourCannotBypassTheReminderByBecomingFirstInTheList() {
        JsonObject state = shortState(); JsonArray pairs = state.getAsJsonArray("pairs");
        for (int i = 0; i < 3; i++) pairs.remove(0);
        assertThrows(IllegalArgumentException.class,
                () -> KeyboardHandler.handleKeyPressed(new Snapshot(state), "Digit1", false, 9.5));
    }

    @Test void resetTargetsCannotBypassTheMarkerAndLongTargetsAreUnchanged() {
        JsonObject state = shortState();
        state.getAsJsonObject("entryContext").addProperty("lowOfDay", 9);
        state.getAsJsonObject("entryContext").addProperty("highOfDay", 11);
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowHandler.handle(new Snapshot(state), "KeyP", false));
        state.getAsJsonObject("entryContext").getAsJsonObject("activeTrade").addProperty("entryBidToReclaim", 10.1);
        assertFalse(WorkflowHandler.handle(new Snapshot(state), "KeyP", false).requests.isEmpty());
        state.addProperty("netQuantity", 100);
        state.getAsJsonObject("entryContext").getAsJsonObject("activeTrade").remove("entryBidToReclaim");
        for (JsonElement item : state.getAsJsonArray("pairs")) for (String leg : new String[]{"STOP", "LIMIT"}) {
            JsonObject order = item.getAsJsonObject().getAsJsonObject(leg);
            order.addProperty("isBuy", false); order.addProperty("price", leg.equals("STOP") ? 9 : 11);
        }
        assertEquals(10, KeyboardHandler.handleKeyPressed(new Snapshot(state), "KeyT", false, 10.5).requests.size());
    }

    @Test void nativeDisplayProjectionCarriesManualEntryBidWithoutRequiringCorePlanFeature() {
        JsonObject view = JsonParser.parseString("{\"type\":\"command_state\",\"symbol\":\"AAPL\",\"account\":{\"positions\":{\"AAPL\":{\"netQuantity\":-100,\"averagePrice\":10}}},\"state\":{\"stateBySymbol\":{\"AAPL\":{\"breakoutTradeStateForShort\":{\"entryBidToReclaim\":9.9}}}}}").getAsJsonObject();
        JsonObject marker = NativeViews.project(view).stream().filter(message -> message.get("type").getAsString()
                .equals("entry_bid_reclaim_config")).findFirst().orElseThrow();
        assertTrue(marker.get("isShort").getAsBoolean()); assertEquals(9.9, marker.get("entryBidToReclaim").getAsDouble());
        assertEquals("real", marker.get("priceUnit").getAsString());
    }
}
