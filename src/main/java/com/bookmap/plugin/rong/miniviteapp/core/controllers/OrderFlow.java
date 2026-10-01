package com.bookmap.plugin.rong.miniviteapp.core.controllers;

import com.bookmap.plugin.rong.miniviteapp.core.algorithms.RiskManager;
import com.bookmap.plugin.rong.miniviteapp.core.algorithms.TakeProfit;
import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.OrderFactory;
import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.bookmap.plugin.rong.miniviteapp.models.Models.*;
import com.google.gson.JsonObject;

/** Flat equity entry slice of controllers/orderFlow.ts. */
public final class OrderFlow {
    private OrderFlow() { }
    public static Plan submitEntry(Snapshot state, JsonObject context, JsonObject definition, JsonObject action,
            boolean isLong, boolean market, double entryPrice, double stopPrice, double multiplier, int requestedCount, String method) {
        double fixed = Models.number(context, "fixedQuantity"), riskPrice = stopPrice;
        String type = market ? "MARKET" : (isLong ? state.currentPrice > entryPrice : state.currentPrice < entryPrice) ? "LIMIT" : "STOP";
        double orderEntry = isLong ? RiskManager.addCents(entryPrice) : RiskManager.minusCents(entryPrice);
        double orderStop = isLong ? RiskManager.minusCents(stopPrice) : RiskManager.addCents(stopPrice);
        int count = fixed > 0 ? requestedCount : Math.min(requestedCount, Math.max(1, Math.min(state.batchCount, (int) Math.round(multiplier * state.batchCount))));
        double shares = fixed > 0 ? fixed : RiskManager.calculateTotalShares(orderEntry, riskPrice, multiplier, Models.number(context, "riskDollars"));
        double cap = Models.number(context, "maxQuantity");
        if (!(fixed > 0) && cap > 0) shares = Math.min(shares, cap);
        JsonObject walls = action.has("orderbook") ? action.getAsJsonObject("orderbook") : null;
        var targets = TakeProfit.getEntryProfitTargets(shares, orderEntry, fixed > 0 ? orderStop : riskPrice, isLong, walls, count);
        double total = 0; for (var element : targets) total += Models.number(element.getAsJsonObject(), "quantity");
        double buyingPower = Models.number(context, "availableBuyingPower");
        Plan plan = new Plan("wall_reversal_entry");
        Models.require(Double.isFinite(buyingPower), "buying-power sizing input missing");
        JsonObject allocation = Workflows.buyingPowerTargets(targets, orderEntry, buyingPower); targets = allocation.getAsJsonArray("targets"); total = allocation.get("totalQuantity").getAsDouble();
        if (allocation.get("insufficient").getAsBoolean()) plan.warnings.add("Native warning: estimated buying power insufficient after half sizing; broker will decide");
        plan.requests.add(new Request("POST", null, OrderFactory.createOneEntryWithMultipleExits(state.symbol, isLong, type, total, orderEntry, targets, orderStop)));
        JsonObject basePlan = definition.getAsJsonObject("basePlan").deepCopy();
        if (!basePlan.has("planConfigs") || !basePlan.get("planConfigs").isJsonObject()) basePlan.add("planConfigs", new JsonObject());
        basePlan.getAsJsonObject("planConfigs").addProperty("sizingCount", count);
        if (!method.isEmpty()) basePlan.addProperty("entryMethod", method);
        JsonObject submit = new JsonObject(); submit.addProperty("totalQuantity", total); submit.add("profitTargets", targets);
        submit.addProperty("isSingleOrder", false); submit.addProperty("tradeBookID", Models.string(definition, "tradebookID"));
        plan.entry = new JsonObject(); plan.entry.addProperty("isLong", isLong); plan.entry.addProperty("useMarketOrder", market);
        plan.entry.addProperty("entryPrice", entryPrice); plan.entry.addProperty("stopOutPrice", stopPrice);
        plan.entry.addProperty("multiplier", multiplier); plan.entry.add("basePlan", basePlan); plan.entry.add("submitEntryResult", submit);
        return plan;
    }
}
