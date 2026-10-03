package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.controllers.EntryHandler;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.EntryRulesChecker;
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
    @Test void commonRulesDoNotAddInputQualityRestrictionsAbsentFromViteApp() {
        var stream = getClass().getResourceAsStream("/direct-entry-fixtures.json"); assertNotNull(stream);
        var fixtures = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonArray();
        var context = fixtures.get(0).getAsJsonObject().getAsJsonObject("state").getAsJsonObject("entryContext").deepCopy();
        context.addProperty("secondsSinceMarketOpen", Double.NaN);
        context.addProperty("openPrice", Double.NaN);
        context.addProperty("vwap", Double.NaN);
        context.addProperty("atr", -1);
        context.addProperty("dailyMaxLoss", 0); // ViteApp does not block nonnegative PnL.
        context.add("volumes", JsonParser.parseString("[-1, 300000, 250000]"));
        assertEquals(1, EntryRulesChecker.checkBasicGlobalEntryRules(context, true, 10));
        var watchAreas = new com.google.gson.JsonArray(); watchAreas.add(Double.NaN);
        context.add("watchAreas", watchAreas);
        var zone = new com.google.gson.JsonObject(); zone.addProperty("low", Double.NaN); zone.addProperty("high", 11);
        var zones = new com.google.gson.JsonArray(); zones.add(zone); context.add("noTradeZones", zones);
        assertEquals(1, EntryRulesChecker.checkBasicGlobalEntryRules(context, true, 10));
    }

}
