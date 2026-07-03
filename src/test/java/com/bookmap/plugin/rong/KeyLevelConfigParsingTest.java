package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class KeyLevelConfigParsingTest {

    @Test
    void keyLevelLabelFieldBecomesCustomLabel() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90, 1000);
        AtomicReference<String> symbolRef = new AtomicReference<>("");
        AtomicReference<List<KeyLevelDefinition>> levelsRef =
                new AtomicReference<>(Collections.emptyList());

        server.registerKeyLevelConfigListener((symbol, levels) -> {
            symbolRef.set(symbol);
            levelsRef.set(levels);
        });

        server.onMessage(null, "{"
                + "\"type\":\"key_levels_config\","
                + "\"symbol\":\"AAPL\","
                + "\"levels\":[{\"price\":185.5,\"label\":\"daily resistance\"}]"
                + "}");

        assertEquals("AAPL", symbolRef.get());
        assertEquals(1, levelsRef.get().size());
        KeyLevelDefinition level = levelsRef.get().get(0);
        assertEquals(185.5, level.getPrice(), 0.00001);
        assertEquals("daily resistance", level.getLabel());
    }
}
