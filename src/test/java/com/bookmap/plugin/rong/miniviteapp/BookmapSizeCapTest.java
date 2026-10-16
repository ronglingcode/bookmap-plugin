package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.controllers.*;
import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.OrderFactory;
import com.bookmap.plugin.rong.miniviteapp.models.Models.*;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BookmapSizeCapTest {
    private JsonObject state(double cap) {
        JsonObject state = JsonParser.parseReader(new InputStreamReader(getClass().getResourceAsStream("/direct-entry-fixtures.json"), StandardCharsets.UTF_8))
                .getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("state").deepCopy();
        JsonObject limit = new JsonObject(); limit.addProperty("maxRiskMultiple", cap);
        state.getAsJsonObject("entryContext").add("bookmapSizeCap", limit);
        state.getAsJsonObject("entryContext").addProperty("riskDollars", 1000);
        state.addProperty("averagePrice", 10);
        return state;
    }
    private Plan entry(boolean isLong, double quantity, double price, double stop) {
        Plan plan = new Plan("wall_reversal_entry");
        plan.requests.add(new Request("POST", null, OrderFactory.createOneEntryWithTwoExits("AAPL", isLong, "LIMIT", quantity, price, isLong ? 12 : 8, stop)));
        return plan;
    }
    private double shares(Plan plan) {
        return plan.requests.stream().mapToDouble(r -> r.body.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("quantity").getAsDouble()).sum();
    }
    @Test void bothSidesUseAbsoluteRatioAndNeverIncreaseSmallerRequests() {
        for (boolean isLong : new boolean[]{true, false}) {
            Snapshot state = new Snapshot(state(5000.0 / 15000));
            Plan plan = entry(isLong, 1000, 10, isLong ? 9 : 11);
            BookmapSizeCap.apply(state, plan); assertEquals(333, shares(plan));
            JsonObject bracket = plan.requests.get(0).body.getAsJsonArray("childOrderStrategies").get(0).getAsJsonObject();
            for (var leg : bracket.getAsJsonArray("childOrderStrategies"))
                assertEquals(333, leg.getAsJsonObject().getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("quantity").getAsInt());
            Plan small = entry(isLong, 100, 10, isLong ? 9 : 11);
            BookmapSizeCap.apply(state, small); assertEquals(100, shares(small));
        }
    }
    @Test void existingPositionAndPendingEntriesConsumeTheBudgetForAdds() {
        JsonObject json = state(.4); json.addProperty("netQuantity", 250);
        json.getAsJsonObject("entryContext").addProperty("lowOfDay", 9);
        json.add("entries", JsonParser.parseString("[{\"orderID\":\"pending\",\"isBuy\":true,\"quantity\":50,\"price\":10,\"exitStopPrice\":9}]"));
        Plan plan = entry(true, 1000, 10, 9);
        BookmapSizeCap.apply(new Snapshot(json), plan); assertEquals(100, shares(plan));
    }
    @Test void replacementDoesNotCountItsOwnOldPendingRiskTwice() {
        JsonObject json = state(.4);
        json.add("entries", JsonParser.parseString("[{\"orderID\":\"pending\",\"isBuy\":true,\"quantity\":400,\"price\":10,\"exitStopPrice\":9}]"));
        Snapshot state = new Snapshot(json); Plan plan = entry(true, 1000, 10, 9);
        plan.requests.set(0, new Request("PUT", state.entries.get(0), plan.requests.get(0).body));
        BookmapSizeCap.apply(state, plan); assertEquals(400, shares(plan));
    }
    @Test void reloadsCannotBypassCapAndMultipleOrdersShareOneBudget() {
        Plan plan = new Plan("reload_partial");
        plan.requests.addAll(entry(true, 500, 10, 9).requests);
        plan.requests.addAll(entry(true, 500, 10, 9).requests);
        BookmapSizeCap.apply(new Snapshot(state(.333)), plan);
        assertEquals(332, shares(plan));
    }
    @Test void noQualifyingOrderBlocksEntriesButLeavesExitsAvailable() {
        Snapshot state = new Snapshot(state(0));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> BookmapSizeCap.apply(state, entry(true, 100, 10, 9)))
                .getMessage().contains("no whole shares"));
        Plan exit = new Plan("flatten"); exit.requests.add(new Request("POST", null, OrderFactory.createSingleOrder("AAPL", "MARKET", 100, 0, false)));
        BookmapSizeCap.apply(state, exit); assertEquals(100, shares(exit));
    }
    @Test void marketSizingIncludesTheProtectiveStopAndCapsBelowOneShareBlock() {
        JsonObject json = state(.4); json.addProperty("currentPrice", 10); json.addProperty("ask", 10.02);
        Plan plan = new Plan("reload_partial");
        plan.requests.add(new Request("POST", null, OrderFactory.createOneEntryWithTwoExits("AAPL", true, "MARKET", 1000, 10, 12, 9)));
        BookmapSizeCap.apply(new Snapshot(json), plan);
        assertTrue(shares(plan) * 1.03 <= 400);
        assertThrows(IllegalArgumentException.class, () -> BookmapSizeCap.apply(new Snapshot(state(.0001)), entry(true, 100, 10, 9)));
        Plan heavy = entry(true, 1500, 10, 9);
        BookmapSizeCap.apply(new Snapshot(state(2)), heavy); assertEquals(1500, shares(heavy));
    }
    @Test void configuredSharesAndHeavierSizingAreClampedAndCapturedTargetsMatch() {
        for (boolean fixed : new boolean[]{true, false}) {
            JsonObject json = state(.4), context = json.getAsJsonObject("entryContext");
            context.addProperty("liquidityScale", 2); context.add("fixedShares", JsonParser.parseString("[2000]"));
            JsonObject action = JsonParser.parseString("{\"tradebook_id\":\"RangeBoundBidReversal\"}").getAsJsonObject();
            action.addProperty("entry_method", fixed ? "2000 sh" : "1 R");
            Snapshot state = new Snapshot(json); Plan plan = EntryHandler.handleEntry(state, action, "");
            BookmapSizeCap.apply(state, plan);
            JsonObject body = plan.requests.get(0).body;
            double price = body.get(body.has("price") ? "price" : "stopPrice").getAsDouble();
            double stop = body.getAsJsonArray("childOrderStrategies").get(0).getAsJsonObject().getAsJsonArray("childOrderStrategies").get(0).getAsJsonObject().get("stopPrice").getAsDouble();
            assertTrue(shares(plan) * Math.abs(price - stop) <= 400);
            assertEquals(shares(plan), plan.entry.getAsJsonObject("submitEntryResult").get("totalQuantity").getAsDouble());
            double targets = 0;
            for (var item : plan.entry.getAsJsonObject("submitEntryResult").getAsJsonArray("profitTargets")) targets += item.getAsJsonObject().get("quantity").getAsDouble();
            assertEquals(shares(plan), targets);
        }
    }
}
