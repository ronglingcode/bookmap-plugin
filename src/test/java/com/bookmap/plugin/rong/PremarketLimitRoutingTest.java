package com.bookmap.plugin.rong;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PremarketLimitRoutingTest {
    static JsonObject key(String symbol, String key, double price) {
        JsonObject action = new JsonObject(); action.addProperty("symbol", symbol); action.addProperty("keyCode", key);
        action.addProperty("source", "bookmap_chart_hotkey"); action.addProperty("price", price); return action;
    }
    @Test void hoverLimitCapturesSharesForExactChartAndRejectsMissingDisabledOrInvalidTicket() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        try {
            AtomicBoolean enabled = new AtomicBoolean(true); server.setPremarketMode(enabled::get);
            List<JsonObject> sent = new ArrayList<>(); server.setTradingDispatch(sent::add);
            for (String symbol : List.of("AMD", "MSFT")) {
                server.registerSymbol(symbol, new OrderBookState(), .01); server.observeSessionBbo(symbol, 10000, 10200, 1);
            }
            server.setPremarketShares("AMD", "7"); server.setPremarketShares("MSFT", "11");
            server.dispatchTradingAction(key("AMD", "KeyL", 105.0037)); server.setPremarketShares("AMD", "99");
            server.dispatchTradingAction(key("MSFT", "KeyL", 99));
            assertEquals(2, sent.size()); assertEquals(7, sent.get(0).get("quantity").getAsInt());
            assertEquals(11, sent.get(1).get("quantity").getAsInt()); assertEquals(105, sent.get(0).get("price").getAsDouble());
            assertEquals("hover_limit", sent.get(0).get("operation").getAsString());
            assertEquals(102, sent.get(0).getAsJsonObject("hoverQuote").get("ask").getAsDouble());
            server.removePremarketShares("AMD"); server.dispatchTradingAction(key("AMD", "KeyL", 105));
            server.setPremarketShares("AMD", "0"); server.dispatchTradingAction(key("AMD", "KeyL", 105));
            enabled.set(false); server.dispatchTradingAction(key("MSFT", "KeyL", 99)); assertEquals(2, sent.size());
        } finally { server.shutdown(); }
    }
    @Test void numberedHotkeyCapturesDisplayedBrokerIdBeforeBackgroundReconciliation() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        try {
            server.setPremarketMode(() -> true); List<JsonObject> sent = new ArrayList<>(); server.setTradingDispatch(sent::add);
            server.acceptLocalMessage(JsonParser.parseString("{\"type\":\"account_state\",\"symbol\":\"AMD\",\"openOrders\":[{\"orderID\":\"123\",\"role\":\"PREMARKET\",\"pairIndex\":1,\"orderType\":\"LIMIT\",\"price\":100,\"quantity\":10,\"isBuy\":true}],\"executions\":[]}").getAsJsonObject());
            server.dispatchTradingAction(key("AMD", "Digit1", 99));
            assertEquals("123", sent.get(0).get("premarketOrderId").getAsString());
        } finally { server.shutdown(); }
    }
}
