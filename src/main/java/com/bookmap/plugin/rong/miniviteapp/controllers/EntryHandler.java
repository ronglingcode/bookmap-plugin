package com.bookmap.plugin.rong.miniviteapp.controllers;

import com.bookmap.plugin.rong.miniviteapp.algorithms.TakeProfit;
import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.bookmap.plugin.rong.miniviteapp.models.Models.*;
import com.bookmap.plugin.rong.miniviteapp.tradebooks.BookmapWallReversal;
import com.google.gson.JsonObject;
import com.bookmap.plugin.rong.miniviteapp.api.Broker;
import java.util.regex.Pattern;

/** First migration: wall-reversal buttons and chart B/S, with no existing symbol exposure. */
public final class EntryHandler {
    private EntryHandler() { }
    private static final Pattern RISK_METHOD = Pattern.compile("(?:^|\\s)(\\d+(?:\\.\\d+)?)\\s*R$", Pattern.CASE_INSENSITIVE);
    public static boolean standardMethod(JsonObject action) {
        String method = field(action, "entry_method", "entryMethod");
        return method.isEmpty() || method.equals("1 R") || method.equals("0.1 R");
    }
    private static String field(JsonObject action, String first, String second) {
        String value = Models.string(action, first); return value.isEmpty() ? Models.string(action, second) : value;
    }
    public static boolean supports(JsonObject action, String key) {
        String id = field(action, "tradebook_id", "tradebookId");
        boolean hover = (key.equals("KeyB") || key.equals("KeyS")) &&
                (Models.string(action, "source").equals("bookmap_chart_hotkey") || Models.string(action, "button_id").startsWith("chart_hotkey:"));
        return BookmapWallReversal.supports(id) && (key.isEmpty() || hover);
    }
    public static Plan handleEntry(Snapshot state, JsonObject action, String key) {
        return handleEntry(state, action, key, false);
    }
    public static Plan handleEntry(Snapshot state, JsonObject action, String key, boolean extended) {
        boolean flat = state.netQuantity == 0 && state.entries.isEmpty() && state.pairs.isEmpty();
        Models.require(extended || flat, "initial native entry requires flat symbol with no pending orders");
        Models.require(!Models.bool(action, "retest_blocked") && !Models.bool(action, "retestBlocked"), "Bookmap retest blocks entry");
        Models.require(!action.has("priceUnit") || Models.string(action, "priceUnit").equals("real"), "unsupported entry price unit");
        JsonObject context = state.entryContext;
        Models.require(context != null, "entry context unavailable");
        String id = field(action, "tradebook_id", "tradebookId"); boolean isLong = BookmapWallReversal.side(id);
        if (!key.isEmpty()) Models.require(isLong == key.equals("KeyB"), "hover key and tradebook side disagree");
        JsonObject definition = null;
        for (var element : context.getAsJsonArray("definitions")) if (Models.string(element.getAsJsonObject(), "tradebookID").equals(id)) definition = element.getAsJsonObject();
        Models.require(definition != null && Models.bool(definition, "enabled") && Models.bool(definition, "isLong") == isLong, "entry tradebook disabled or unsupported");
        double high = Models.number(context, "highOfDay"), low = Models.number(context, "lowOfDay");
        Models.require(Models.positive(high) && Models.positive(low) && high >= low, "day levels unavailable");
        if (action.has("orderbook")) {
            var walls = action.getAsJsonObject("orderbook");
            Models.require(!walls.has("priceUnit") || Models.string(walls, "priceUnit").equals("real"), "unsupported wall price unit");
        }
        if (action.has("bookmapDayHighLow")) {
            var day = action.getAsJsonObject("bookmapDayHighLow");
            Models.require(!day.has("priceUnit") || Models.string(day, "priceUnit").equals("real"), "unsupported day-level price unit");
            double dayHigh = Models.number(day, "high"), dayLow = Models.number(day, "low");
            if (Models.positive(dayHigh) && Models.positive(dayLow)) { high = Math.max(high, Math.ceil(dayHigh * 100) / 100); low = Math.min(low, Math.floor(dayLow * 100) / 100); }
        }
        boolean market = key.isEmpty() && (Models.bool(action, "use_market_order") || Models.bool(action, "useMarketOrder"));
        double custom = Models.number(context, "customEntryPrice");
        double entry = market ? state.currentPrice : key.isEmpty() ? (custom > 0 ? custom : isLong ? high : low) : Models.number(action, "price");
        entry = key.isEmpty() && !market ? (isLong ? Math.ceil(entry * 100) : Math.floor(entry * 100)) / 100 : TakeProfit.round(entry);
        double stop = Models.number(context, isLong ? "customStopLong" : "customStopShort");
        if (stop == 0) stop = isLong ? low : high;
        Models.require(Models.positive(entry) && Models.positive(stop) && (isLong ? stop < entry : stop > entry), "invalid entry or protective stop");
        BookmapWallReversal.checkEntryPrice(definition, entry, isLong);
        String method = field(action, "entry_method", "entryMethod");
        Models.require(extended || standardMethod(action), "unsupported entry method");
        double methodMultiplier = 1;
        var match = RISK_METHOD.matcher(method.trim());
        if (match.find()) {
            double parsed = Double.parseDouble(match.group(1));
            if (Models.positive(parsed)) methodMultiplier = parsed;
        }
        JsonObject ruleContext = context;
        if (extended) {
            ruleContext = context.deepCopy();
            ruleContext.addProperty("liquidityScale", Models.number(context, "liquidityScale")
                    * ExtendedEntryRules.nextEntryMultiplier(state, isLong, entry));
        }
        double multiplier = EntryRulesChecker.checkBasicGlobalEntryRules(ruleContext, isLong, entry) * methodMultiplier;
        int count = methodMultiplier == 0.1 ? 1 : state.batchCount;
        if (market) {
            double estimate = Models.number(action, "estimated_entry_price");
            if (!Models.positive(estimate)) estimate = Models.number(action, "estimatedEntryPrice");
            if (Models.positive(estimate)) entry = isLong ? Math.max(state.currentPrice, estimate) : Math.min(state.currentPrice, estimate);
        } else entry = isLong ? Math.max(entry, state.ask) : Math.min(entry, state.bid);
        Models.require(Models.positive(entry) && (isLong ? stop < entry : stop > entry), "quote crosses protective stop");
        Plan plan = OrderFlow.submitEntry(state, context, definition, action, isLong, market, entry, stop, multiplier, count, method);
        plan.requireFlatEntry = flat;
        plan.entry.addProperty("preserveExistingTrade", state.netQuantity != 0 && (state.netQuantity > 0) == isLong);
        if (extended) {
            var opening = plan.requests.remove(0);
            if (state.netQuantity != 0 && (state.netQuantity > 0) != isLong) for (var pair : state.pairs) {
                Models.require(pair.marketLeg().isBuy == isLong, "exit side disagrees with opposite position");
                if (market) Broker.instantOutOneExitPair(plan, pair);
                else Broker.replaceExitPairWithNewPrice(plan, pair, entry, true);
            }
            plan.requests.add(opening);
            // ViteApp submits the new protected entry before cancelling old same-direction entries.
            for (var pending : state.entries) if (pending.isBuy == isLong) Broker.cancelOrders(plan, pending);
        }
        plan.entry.addProperty("highOfDay", high); plan.entry.addProperty("lowOfDay", low);
        return plan;
    }
    public static Plan handleDirectionalEntry(Snapshot state, JsonObject action, String key) {
        Models.require(!Models.string(action, "source").equals("bookmap_chart_hotkey")
                && !Models.string(action, "button_id").startsWith("chart_hotkey:"), "chart B/S requires a wall-reversal tradebook");
        Models.require(state.entryContext != null, "entry context unavailable");
        JsonObject chosen = null;
        for (var element : state.entryContext.getAsJsonArray("definitions")) {
            var definition = element.getAsJsonObject();
            if (Models.bool(definition, "enabled") && Models.bool(definition, "isLong") == key.equals("KeyB")) {
                Models.require(chosen == null, "multiple tradebooks; use button instead");
                chosen = definition;
            }
        }
        Models.require(chosen != null, "no enabled entry tradebook");
        JsonObject selected = action.deepCopy();
        selected.addProperty("tradebook_id", Models.string(chosen, "tradebookID"));
        selected.addProperty("use_market_order", Models.bool(action, "shiftKey") || Models.bool(action, "shift_key"));
        return handleEntry(state, selected, "", true);
    }
}
