package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.controllers.KeyboardHandler;
import com.bookmap.plugin.rong.miniviteapp.models.Models.Snapshot;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionPlanTest {
    @Test void doubleMarketOutSelectsDistinctSmallestPairsAndClampsToAvailablePairs() {
        var fixtures = JsonParser.parseReader(new InputStreamReader(getClass()
                .getResourceAsStream("/direct-execution-fixtures.json"), StandardCharsets.UTF_8)).getAsJsonArray();
        var json = fixtures.get(0).getAsJsonObject().getAsJsonObject("state").deepCopy();
        var pairs = json.getAsJsonArray("pairs");
        for (String leg : new String[]{"STOP", "LIMIT"}) {
            pairs.get(0).getAsJsonObject().getAsJsonObject(leg).addProperty("quantity", 30);
            pairs.get(1).getAsJsonObject().getAsJsonObject(leg).addProperty("quantity", 5);
            pairs.get(2).getAsJsonObject().getAsJsonObject(leg).addProperty("quantity", 5);
        }
        var state = new Snapshot(json);
        var single = KeyboardHandler.handleKeyPressed(state, "KeyM", false, 0);
        var doubled = com.bookmap.plugin.rong.miniviteapp.core.controllers.Handler.marketOutPartials(state, 2);
        assertEquals(1, single.requests.size());
        assertEquals(2, doubled.requests.size());
        assertEquals(single.requests.get(0).orderId, doubled.requests.get(0).orderId);
        assertNotEquals(doubled.requests.get(0).orderId, doubled.requests.get(1).orderId);
        assertEquals("204", doubled.requests.get(1).orderId);
        pairs.remove(2); pairs.remove(0);
        assertEquals(1, com.bookmap.plugin.rong.miniviteapp.core.controllers.Handler
                .marketOutPartials(new Snapshot(json), 2).requests.size());
    }
    @Test void sanitizedFixturesMatchProductionTsDecisionsAndPayloads() {
        var stream = getClass().getResourceAsStream("/direct-execution-fixtures.json");
        assertNotNull(stream);
        var fixtures = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonArray();
        for (var element : fixtures) {
            var fixture = element.getAsJsonObject();
            String name = fixture.get("name").getAsString();
            var state = new Snapshot(fixture.getAsJsonObject("state"));
            var key = fixture.get("key").getAsString();
            boolean shift = fixture.get("shift").getAsBoolean();
            double price = fixture.get("price").isJsonNull() ? Double.NaN : fixture.get("price").getAsDouble();
            if (fixture.get("error").getAsBoolean()) {
                assertThrows(IllegalArgumentException.class, () -> KeyboardHandler.handleKeyPressed(state, key, shift, price), name);
            } else {
                var plan = KeyboardHandler.handleKeyPressed(state, key, shift, price);
                assertEquals(fixture.getAsJsonArray("requests"), plan.toJson(), name);
            }
        }
    }
    @Test void entryAndSwapActionsAreNeverNative() {
        for (var key : new String[]{"KeyB", "KeyS", "KeyA", "KeyW", "KeyV", ""}) {
            assertFalse(KeyboardHandler.supports(key, false), key);
        }
    }
}
