package com.bookmap.plugin.rong;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeTradingActionRoutingTest {
    @Test void chartAndButtonEntriesUseLocalRetestReadiness() {
        List<JsonObject> actions = new ArrayList<>();
        SignalWebSocketServer server = new SignalWebSocketServer(0, 97) { @Override public void broadcast(String text) { } };
        try {
            server.setTradingDispatch(actions::add);
            List<String> notifications = new ArrayList<>(); server.setRetestNotification((symbol, text) -> notifications.add(symbol + ":" + text));
            server.acceptLocalMessage(com.google.gson.JsonParser.parseString("{\"type\":\"key_levels_config\",\"symbol\":\"AAPL\",\"levels\":[],\"waitForBidRetest\":\"yes\",\"waitForOfferRetest\":\"warning\"}").getAsJsonObject());
            JsonObject action = new JsonObject(); action.addProperty("symbol", "AAPL"); action.addProperty("keyCode", "KeyB"); action.addProperty("retest_blocked", false);
            server.dispatchTradingAction(action); assertTrue(actions.get(0).get("retest_blocked").getAsBoolean());
            action.addProperty("keyCode", "KeyS"); server.dispatchTradingAction(action);
            assertFalse(actions.get(1).get("retest_blocked").getAsBoolean()); assertEquals("wait for offer retest", actions.get(1).get("retest_warning").getAsString());
            action.remove("keyCode"); action.addProperty("tradebook_id", "RangeBoundBidReversal"); action.addProperty("sideIsLong", true); server.dispatchTradingAction(action);
            assertTrue(actions.get(2).get("retest_blocked").getAsBoolean()); server.markEntryRetestSatisfied("AAPL", true); server.dispatchTradingAction(action);
            assertFalse(actions.get(3).get("retest_blocked").getAsBoolean()); assertFalse(actions.get(3).has("retest_warning"));
            server.markEntryRetestSatisfied("AAPL", true); assertEquals(List.of("AAPL:bid retest done"), notifications);
        } finally { server.shutdown(); }
    }

    @Test void everyLocalActionUsesRuntimeAndExternalMessagesCannotOverwriteLocalState() {
        List<String> broadcasts = new ArrayList<>(); List<JsonObject> actions = new ArrayList<>();
        SignalWebSocketServer server = new SignalWebSocketServer(0, 97) { @Override public void broadcast(String text) { broadcasts.add(text); } };
        try {
            server.setTradingDispatch(actions::add);
            for (String key : new String[]{"KeyC", "KeyF", "KeyB", "KeyS", "KeyA", "KeyW", "KeyQ", "KeyP", "KeyJ", "KeyK", "KeyL", "KeyZ", "Space", "Digit1", "Numpad1"}) {
                JsonObject action = new JsonObject(); action.addProperty("type", "custom_button_click"); action.addProperty("symbol", "AAPL"); action.addProperty("keyCode", key); server.dispatchTradingAction(action);
            }
            assertEquals(15, actions.size()); assertTrue(broadcasts.isEmpty());
            List<CorePlanConfigDefinition> received = new ArrayList<>(); server.registerCorePlanConfigListener("AAPL", received::add);
            String json = "{\"type\":\"core_plan_config\",\"symbol\":\"AAPL\",\"hasActiveTrade\":false}";
            server.onMessage(null, json); assertTrue(received.isEmpty());
            server.acceptLocalMessage(com.google.gson.JsonParser.parseString(json).getAsJsonObject()); assertEquals(1, received.size());
            server.onMessage(null, "{\"type\":\"execution_token\",\"accessToken\":\"untrusted\"}"); assertEquals(15, actions.size());
        } finally { server.shutdown(); }
    }
}
