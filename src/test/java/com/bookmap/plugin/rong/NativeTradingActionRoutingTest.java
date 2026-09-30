package com.bookmap.plugin.rong;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeTradingActionRoutingTest {
    @Test
    void stableActionsStayNativeAndOnlyExtendedActionsFollowTheFlag() {
        List<String> broadcasts = new ArrayList<>();
        SignalWebSocketServer server = new SignalWebSocketServer(0, 97) {
            @Override public void broadcast(String text) { broadcasts.add(text); }
        };
        try {
            for (String key : new String[]{"KeyC", "KeyF", "KeyM", "Digit1", "Numpad1", "KeyG", "KeyH", "KeyT"}) {
                JsonObject action = new JsonObject();
                action.addProperty("type", "custom_button_click");
                action.addProperty("symbol", "AAPL");
                action.addProperty("keyCode", key);
                server.dispatchTradingAction(action);
            }
            JsonObject button = new JsonObject();
            button.addProperty("type", "custom_button_click");
            button.addProperty("symbol", "AAPL");
            button.addProperty("tradebook_id", "RangeBoundBidReversal");
            server.dispatchTradingAction(button);
            assertTrue(broadcasts.isEmpty());
            for (String key : new String[]{"KeyA", "KeyW"}) {
                JsonObject action = button.deepCopy(); action.addProperty("keyCode", key);
                server.dispatchTradingAction(action);
            }
            assertEquals(2, broadcasts.size());
            assertEquals("custom_button_click", com.google.gson.JsonParser.parseString(broadcasts.get(0)).getAsJsonObject().get("type").getAsString());
            server.setExperimentalDirectExecution(true); broadcasts.clear();
            JsonObject reload = button.deepCopy(); reload.addProperty("keyCode", "KeyA");
            server.dispatchTradingAction(reload); server.dispatchTradingAction(button);
            assertTrue(broadcasts.isEmpty());
            server.setExperimentalDirectExecution(false);
            var status = com.google.gson.JsonParser.parseString(broadcasts.get(0)).getAsJsonObject();
            assertTrue(status.get("enabled").getAsBoolean());
            assertFalse(status.get("extendedEnabled").getAsBoolean());
        } finally {
            server.shutdown();
        }
    }
}
