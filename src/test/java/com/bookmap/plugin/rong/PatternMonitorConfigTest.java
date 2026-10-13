package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.NativeViews;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class PatternMonitorConfigTest {
    @Test void nativePlanProjectionEnablesMonitorAndLateSubscribersReceiveConfiguration() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        JsonObject view = JsonParser.parseString("{\"type\":\"market_ready\",\"symbol\":\"TEST\",\"plan\":{\"bookmapPatternsToMonitor\":[\"falseBreakout\"]}}").getAsJsonObject();
        NativeViews.project(view).forEach(server::acceptLocalMessage);
        AtomicReference<List<String>> patterns = new AtomicReference<>();
        SignalWebSocketServer.PatternMonitorConfigListener listener = (symbol, values) -> {
            assertEquals("TEST", symbol); patterns.set(values);
        };
        server.registerPatternMonitorConfigListener(listener);
        assertEquals(List.of("falseBreakout"), patterns.get());
        assertThrows(UnsupportedOperationException.class, () -> patterns.get().add("anotherPattern"));
        view.add("plan", new JsonObject()); NativeViews.project(view).forEach(server::acceptLocalMessage);
        assertEquals(List.of(), patterns.get());
        server.unregisterPatternMonitorConfigListener(listener);
        view.getAsJsonObject("plan").add("bookmapPatternsToMonitor", JsonParser.parseString("[\"falseBreakout\"]"));
        NativeViews.project(view).forEach(server::acceptLocalMessage);
        assertEquals(List.of(), patterns.get());
    }

    @Test void socketConfigurationIsIsolatedBySymbolAndOmittedFieldDisablesMonitor() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        AtomicReference<List<String>> patterns = new AtomicReference<>();
        server.registerPatternMonitorConfigListener((symbol, values) -> { if (symbol.equals("TEST")) patterns.set(values); });
        server.acceptLocalMessage(JsonParser.parseString("{\"type\":\"key_levels_config\",\"symbol\":\"TEST\",\"levels\":[],\"bookmapPatternsToMonitor\":[\"falseBreakout\"]}").getAsJsonObject());
        assertEquals(List.of("falseBreakout"), patterns.get());
        server.acceptLocalMessage(JsonParser.parseString("{\"type\":\"key_levels_config\",\"symbol\":\"OTHER\",\"levels\":[]}").getAsJsonObject());
        assertEquals(List.of("falseBreakout"), patterns.get());
        server.acceptLocalMessage(JsonParser.parseString("{\"type\":\"key_levels_config\",\"symbol\":\"TEST\",\"levels\":[]}").getAsJsonObject());
        assertEquals(List.of(), patterns.get());
    }
}
