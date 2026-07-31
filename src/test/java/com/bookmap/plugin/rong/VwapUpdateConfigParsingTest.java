package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class VwapUpdateConfigParsingTest {

    @Test
    void validUpdateIsDeliveredToTheMatchingSymbolListener() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90, 1000);
        AtomicReference<VwapUpdateDefinition> updateRef = new AtomicReference<>();
        server.registerVwapUpdateListener("AAPL", updateRef::set);

        server.onMessage(null, "{"
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
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90, 1000);
        AtomicReference<VwapUpdateDefinition> updateRef = new AtomicReference<>();
        server.registerVwapUpdateListener("AAPL", updateRef::set);

        server.onMessage(null, updateJson(101.0, 1785244020000L, 1785244020500L));
        server.onMessage(null, updateJson(99.0, 1785243960000L, 1785244030000L));

        assertEquals(101.0, updateRef.get().getVwap(), 0.00001);
    }

    @Test
    void invalidVwapIsRejected() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90, 1000);
        AtomicReference<VwapUpdateDefinition> updateRef = new AtomicReference<>();
        server.registerVwapUpdateListener("AAPL", updateRef::set);

        server.onMessage(null, updateJson(0, 1785243960000L, 1785243960500L));

        assertNull(updateRef.get());
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
}
