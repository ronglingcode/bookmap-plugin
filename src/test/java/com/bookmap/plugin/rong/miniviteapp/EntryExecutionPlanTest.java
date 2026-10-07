package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.controllers.EntryHandler;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.EntryRulesChecker;
import com.bookmap.plugin.rong.miniviteapp.core.marketdata.StartupEligibility;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.bookmap.plugin.rong.miniviteapp.models.Models.Snapshot;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntryExecutionPlanTest {
    private JsonObject entryState() {
        return JsonParser.parseReader(new InputStreamReader(getClass().getResourceAsStream("/direct-entry-fixtures.json"), StandardCharsets.UTF_8))
                .getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("state").deepCopy();
    }
    @Test void entryRulesPreserveStartupAndWatchlistReasonsAndShowPriceAndRiskValues() {
        JsonObject context = entryState().getAsJsonObject("entryContext");
        String breakout = "previous consolidation breakout on 2026-10-05: open 630.605 inside [621, 631], close 631.75";
        context.addProperty("startupBlockReason", breakout);
        assertEquals("checkRule: startup eligibility: " + breakout, assertThrows(IllegalArgumentException.class,
                () -> EntryRulesChecker.checkBasicGlobalEntryRules(context, true, 10)).getMessage());
        context.addProperty("startupBlockReason", "");
        context.addProperty("watchlistBlockReason", "more than 1 stocks in watchlist: AMD, AAPL");
        assertEquals("checkRule: more than 1 stocks in watchlist: AMD, AAPL", assertThrows(IllegalArgumentException.class,
                () -> EntryRulesChecker.checkBasicGlobalEntryRules(context, true, 10)).getMessage());
        context.addProperty("watchlistBlockReason", "");
        context.addProperty("realizedPnl", -4000);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> EntryRulesChecker.checkBasicGlobalEntryRules(context, true, 10)).getMessage()
                .contains("realized P&L=-4000.0, daily max loss=4000.0"));
        context.addProperty("realizedPnl", 0);
        context.add("watchAreas", JsonParser.parseString("[10.1]"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> EntryRulesChecker.checkBasicGlobalEntryRules(context, true, 10)).getMessage()
                .contains("entry price 10.0 is near against watch level 10.1"));
        context.add("watchAreas", new JsonArray());
        context.add("noTradeZones", JsonParser.parseString("[{\"low\":9.9,\"high\":10.1}]"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> EntryRulesChecker.checkBasicGlobalEntryRules(context, true, 10)).getMessage()
                .contains("entry price 10.0 is inside no trade zone 9.9 - 10.1"));
    }
    @Test void amdWithZeroPremarketVolumeCanBuildEntryWhileOtherStartupRulesStillApply() {
        JsonObject state = entryState(); state.addProperty("symbol", "AMD");
        JsonObject plan = JsonParser.parseString("{\"symbol\":\"AMD\",\"marketCapInMillions\":10000}").getAsJsonObject();
        JsonObject stats = JsonParser.parseString("{\"lastDayShares\":0,\"previousDaysSharesAverage\":1000000}").getAsJsonObject();
        String reason = StartupEligibility.evaluate(plan, 10, 100000000, stats, new JsonArray());
        assertEquals("", reason);
        state.getAsJsonObject("entryContext").addProperty("startupBlockReason", reason);
        JsonObject action = JsonParser.parseString("{\"tradebook_id\":\"RangeBoundBidReversal\",\"entry_method\":\"1 R\"}").getAsJsonObject();
        assertEquals(1, EntryHandler.handleEntry(new Snapshot(state), action, "").requests.size());
        plan.addProperty("symbol", "AAPL");
        assertEquals("premarket shares below 500000 hard floor", StartupEligibility.evaluate(plan, 10, 100000000, stats, new JsonArray()));
    }
    @Test void onlyTenthRiskUsesOnePartialWhileOtherMethodsUseFullPartials() {
        var stream = getClass().getResourceAsStream("/direct-entry-fixtures.json"); assertNotNull(stream);
        var fixture = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonArray().get(0).getAsJsonObject();
        for (boolean isLong : new boolean[]{true, false}) {
            for (double risk : new double[]{0.1, 0.2, 0.3, 0.5, 1}) {
                var action = fixture.getAsJsonObject("action").deepCopy();
                action.addProperty("entry_method", risk + " R");
                action.addProperty("tradebook_id", isLong ? "RangeBoundBidReversal" : "RangeBoundOfferReversal");
                var state = fixture.getAsJsonObject("state").deepCopy();
                state.getAsJsonObject("entryContext").addProperty("customEntryPrice", 10);
                var plan = EntryHandler.handleEntry(new Snapshot(state), action, "");
                assertEquals(risk, plan.entry.get("multiplier").getAsDouble());
                int count = risk == 0.1 ? 1 : 10;
                assertEquals(count, plan.entry.getAsJsonObject("basePlan").getAsJsonObject("planConfigs").get("sizingCount").getAsInt());
                assertEquals(count, plan.entry.getAsJsonObject("submitEntryResult").getAsJsonArray("profitTargets").size());
                assertEquals(Math.floor(1960 * risk), plan.entry.getAsJsonObject("submitEntryResult").get("totalQuantity").getAsDouble());
                assertEquals(count, plan.toJson().get(0).getAsJsonObject().getAsJsonObject("body").getAsJsonArray("childOrderStrategies").size());
            }
        }
    }
    @Test void reducedLiquidityKeepsFullPartialCount() {
        var state = entryState();
        state.getAsJsonObject("entryContext").add("volumes", JsonParser.parseString("[500000,80000,60000]"));
        var action = JsonParser.parseString("{\"tradebook_id\":\"RangeBoundBidReversal\",\"entry_method\":\"0.2 R\"}").getAsJsonObject();
        var plan = EntryHandler.handleEntry(new Snapshot(state), action, "");
        assertEquals(0.1, plan.entry.get("multiplier").getAsDouble());
        assertEquals(10, plan.entry.getAsJsonObject("basePlan").getAsJsonObject("planConfigs").get("sizingCount").getAsInt());
        assertEquals(10, plan.entry.getAsJsonObject("submitEntryResult").getAsJsonArray("profitTargets").size());
    }
    @Test void fixturesMatchNativeEntryPolicy() {
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
