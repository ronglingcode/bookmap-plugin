package com.bookmap.plugin.rong.miniviteapp.core.controllers;

import com.bookmap.plugin.rong.miniviteapp.libraries.broker.Broker;
import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.OrderFactory;
import com.bookmap.plugin.rong.miniviteapp.core.algorithms.TakeProfit;
import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.bookmap.plugin.rong.miniviteapp.models.Models.*;
import com.bookmap.plugin.rong.miniviteapp.core.tradebooks.BookmapWallReversal;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Native mirrors of handler.reloadPartialPressed and swapPositionKeyPressed. */
public final class ExtendedHandler {
    private ExtendedHandler() { }

    public static Plan reload(Snapshot state, boolean shift, double price) {
        JsonObject context = state.entryContext;
        Models.require(context != null, "partial entry context unavailable");
        boolean isLong = Models.bool(context, "reloadIsLong");
        JsonObject direction = context.getAsJsonObject(isLong ? "longState" : "shortState");
        Models.require(direction != null, "partial trade state unavailable");
        if (shift) price = state.currentPrice;
        else if (!Models.positive(price)) price = Models.number(context, "crosshairPrice");
        Models.require(Models.positive(price), "partial entry price unavailable");
        price = TakeProfit.round(price);
        double stop = Models.number(context, isLong ? "customStopLong" : "customStopShort");
        if (stop == 0) stop = Models.number(context, isLong ? "lowOfDay" : "highOfDay");
        stop = (isLong ? Math.floor(stop * 100) : Math.ceil(stop * 100)) / 100;
        Models.require(Models.positive(stop) && (isLong ? stop < price : stop > price), "invalid partial protective stop");
        double count = Models.number(direction, "partialsCount");
        double initial = Models.number(direction, "initialQuantity");
        Models.require(Models.positive(count), "partial count unavailable");
        double quantity = initial > 0 ? Math.round(initial / count) : Models.number(context, "lastExitSize");
        if (!(quantity > 0)) quantity = Math.round(Math.abs(state.netQuantity) / count);
        Models.require(quantity > 0, "partial quantity is zero");
        String id = Models.string(direction, "tradebookID");
        for (var element : context.getAsJsonArray("definitions")) {
            var definition = element.getAsJsonObject();
            if (!id.isEmpty() && Models.string(definition, "tradebookID").equals(id))
                BookmapWallReversal.checkEntryPrice(definition, price, isLong);
        }
        ExtendedEntryRules.checkPartial(state, direction, isLong, quantity, price, stop);
        // Broker.submitEntryOrderWithBracket checks attendance even after the low-risk override.
        Models.require(Models.bool(context, "attendanceAllowed"), "attendance blocks partial entry");
        String type = shift ? "MARKET" : (isLong ? price > state.currentPrice : price < state.currentPrice) ? "STOP" : "LIMIT";
        double target = isLong ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        for (var pair : state.pairs) if (pair.limit != null && Models.positive(pair.limit.price))
            target = isLong ? Math.min(target, pair.limit.price) : Math.max(target, pair.limit.price);
        if (state.pairs.isEmpty()) target = TakeProfit.round(price + (isLong ? 1 : -1) * Math.abs(price - stop));
        Models.require(Models.positive(target), "partial target unavailable");
        Plan plan = new Plan("reload_partial");
        if (state.entries.size() > 3) Broker.cancelOrders(plan, state.entries.get(0));
        plan.requests.add(new Request("POST", null, OrderFactory.createOneEntryWithTwoExits(
                state.symbol, isLong, type, quantity, price, target, stop)));
        return plan;
    }

    public static Plan swap(Snapshot state) {
        Models.require(state.netQuantity != 0, "no position to swap");
        JsonObject context = state.entryContext;
        Models.require(context != null && context.has("activeBasePlan") && !context.get("activeBasePlan").isJsonNull(), "swap requires active base plan");
        boolean isLong = state.netQuantity > 0;
        for (var pair : state.pairs) Models.require(pair.marketLeg().isBuy == !isLong, "exit side disagrees with swap position");
        Plan plan = new Plan("swap");
        boolean hasEntry = state.entries.stream().anyMatch(order -> order.isBuy == isLong);
        if (hasEntry) {
            Models.require(state.pairs.size() >= 2, "swap with pending entry needs two exit pairs");
            double closing = 0;
            for (int i = 0; i < state.pairs.size() - 1; i++) {
                var leg = state.pairs.get(i).marketLeg();
                closing += leg.quantity; Broker.cancelOrders(plan, leg);
            }
            var close = new Request("POST", null, OrderFactory.createSingleOrder(state.symbol, "MARKET", closing, 0, !isLong));
            close.delayBeforeMs = 750; plan.requests.add(close);
            return plan;
        }
        plan.clearPending = true;
        double remaining = Math.abs(state.netQuantity);
        for (var pair : state.pairs) {
            Broker.instantOutOneExitPair(plan, pair); remaining -= pair.quantity();
        }
        Models.require(remaining >= 0, "swap exits exceed position");
        if (remaining > 0) plan.requests.add(new Request("POST", null,
                OrderFactory.createSingleOrder(state.symbol, "MARKET", remaining, 0, !isLong)));
        double high = Models.number(context, "highOfDay"), low = Models.number(context, "lowOfDay");
        Models.require(Models.positive(high) && Models.positive(low) && high > low, "swap day range unavailable");
        JsonObject direction = context.getAsJsonObject(isLong ? "longState" : "shortState");
        Models.require(direction != null, "swap trade state unavailable");
        double initial = Models.number(direction, "initialQuantity"), riskDollars = Models.number(context, "riskDollars");
        Models.require(Models.positive(initial) && Models.positive(riskDollars), "swap sizing inputs unavailable");
        double multiplier = Math.round((high - low) * initial / riskDollars * 1000) / 1000.0;
        double price = isLong ? Math.max(high, state.ask) : Math.min(low, state.bid), stop = isLong ? low : high;
        JsonObject definition = new JsonObject(); definition.addProperty("tradebookID", "");
        definition.add("basePlan", context.getAsJsonObject("activeBasePlan"));
        double requested = Models.number(definition.getAsJsonObject("basePlan").getAsJsonObject("planConfigs"), "sizingCount");
        int count = requested > 0 ? (int) requested : state.batchCount;
        Plan reentry = OrderFlow.submitEntry(state, context, definition, new JsonObject(), isLong, false, price, stop, multiplier, count, "");
        reentry.requests.get(0).delayBeforeMs = 500;
        plan.requests.addAll(reentry.requests); plan.entry = reentry.entry;
        plan.entry.addProperty("preserveExistingTrade", false);
        plan.entry.addProperty("highOfDay", high); plan.entry.addProperty("lowOfDay", low);
        return plan;
    }
}
