package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class VwapUpdateConfigParsingTest {

    @Test
    void validUpdateIsDeliveredToTheMatchingSymbolListener() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        AtomicReference<VwapUpdateDefinition> updateRef = new AtomicReference<>();
        server.registerVwapUpdateListener("AAPL", updateRef::set);

        localMessage(server, "{"
                + "\"type\":\"vwap_update\","
                + "\"priceUnit\":\"real\","
                + "\"symbol\":\"AAPL\","
                + "\"vwap\":100.25,"
                + "\"effectiveTimeMs\":1785243960000,"
                + "\"sentAtMs\":1785243960500"
                + "}");

        VwapUpdateDefinition update = updateRef.get();
        assertEquals("AAPL", update.getSymbol());
        assertEquals(100.25, update.getVwap(), 0.00001);
        assertEquals(1785243960000L, update.getEffectiveTimeMs());
    }

    @Test
    void staleUpdateIsRejected() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        AtomicReference<VwapUpdateDefinition> updateRef = new AtomicReference<>();
        server.registerVwapUpdateListener("AAPL", updateRef::set);

        localMessage(server, updateJson(101.0, 1785244020000L, 1785244020500L));
        localMessage(server, updateJson(99.0, 1785243960000L, 1785244030000L));

        assertEquals(101.0, updateRef.get().getVwap(), 0.00001);
    }

    @Test
    void invalidVwapIsRejected() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        AtomicReference<VwapUpdateDefinition> updateRef = new AtomicReference<>();
        server.registerVwapUpdateListener("AAPL", updateRef::set);

        localMessage(server, updateJson(0, 1785243960000L, 1785243960500L));

        assertNull(updateRef.get());
    }

    @Test
    void repeatedClosedPointDoesNotNotifyAgainButChangedValueDoes() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        java.util.List<VwapUpdateDefinition> updates = new java.util.ArrayList<>();
        server.registerVwapUpdateListener("AAPL", updates::add);
        localMessage(server, updateJson(101, 1785244020000L, 1785244020500L));
        localMessage(server, updateJson(101, 1785244020000L, 1785244030000L));
        assertEquals(1, updates.size());
        localMessage(server, updateJson(102, 1785244020000L, 1785244031000L));
        assertEquals(2, updates.size());
    }

    private static String updateJson(double vwap, long effectiveTimeMs, long sentAtMs) {
        return "{"
                + "\"type\":\"vwap_update\","
                + "\"priceUnit\":\"real\","
                + "\"symbol\":\"AAPL\","
                + "\"vwap\":" + vwap + ","
                + "\"effectiveTimeMs\":" + effectiveTimeMs + ","
                + "\"sentAtMs\":" + sentAtMs
                + "}";
    }
    private static void localMessage(com.bookmap.plugin.rong.SignalWebSocketServer server, String json) { server.acceptLocalMessage(com.google.gson.JsonParser.parseString(json).getAsJsonObject()); }
}
