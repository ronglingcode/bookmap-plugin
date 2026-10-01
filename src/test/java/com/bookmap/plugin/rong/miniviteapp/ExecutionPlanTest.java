package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.controllers.KeyboardHandler;
import com.bookmap.plugin.rong.miniviteapp.models.Models.Snapshot;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionPlanTest {
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
