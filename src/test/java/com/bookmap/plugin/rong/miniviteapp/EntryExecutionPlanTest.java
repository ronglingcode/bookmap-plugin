package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.controllers.EntryHandler;
import com.bookmap.plugin.rong.miniviteapp.models.Models.Snapshot;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntryExecutionPlanTest {
    @Test void fixturesMatchProductionTsEntryHelpers() {
        var stream = getClass().getResourceAsStream("/direct-entry-fixtures.json"); assertNotNull(stream);
        var fixtures = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonArray();
        for (var element : fixtures) {
            var fixture = element.getAsJsonObject(); var state = new Snapshot(fixture.getAsJsonObject("state"));
            var action = fixture.getAsJsonObject("action"); String key = fixture.get("key").getAsString(), name = fixture.get("name").getAsString();
            assertTrue(EntryHandler.supports(action, key), name);
            if (fixture.get("error").getAsBoolean()) assertThrows(IllegalArgumentException.class, () -> EntryHandler.handleEntry(state, action, key), name);
            else {
                var plan = EntryHandler.handleEntry(state, action, key);
                assertEquals(fixture.getAsJsonArray("requests"), plan.toJson(), name);
                for (var field : fixture.getAsJsonObject("entry").entrySet()) assertEquals(field.getValue(), plan.entry.get(field.getKey()), name + " " + field.getKey());
            }
        }
    }
}
