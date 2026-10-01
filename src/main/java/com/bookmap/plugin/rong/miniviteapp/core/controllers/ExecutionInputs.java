package com.bookmap.plugin.rong.miniviteapp.core.controllers;

import com.bookmap.plugin.rong.miniviteapp.core.account.TradeLedger;
import com.bookmap.plugin.rong.miniviteapp.core.configuration.TradingConfig;
import com.bookmap.plugin.rong.miniviteapp.core.marketdata.MarketClock;
import com.bookmap.plugin.rong.miniviteapp.core.state.TradeState;
import com.google.gson.*;
import java.util.ArrayList;
import java.util.List;

/** Mirrors TS createExecutionInputs. Every input is already loaded; no vendor or UI calls. */
public final class ExecutionInputs {
    private ExecutionInputs() { }
    private static JsonObject object(JsonObject value, String key) { return value.has(key) && value.get(key).isJsonObject() ? value.getAsJsonObject(key) : new JsonObject(); }
    private static JsonArray array(JsonObject value, String key) { return value.has(key) && value.get(key).isJsonArray() ? value.getAsJsonArray(key) : new JsonArray(); }
    private static double number(JsonObject value, String key) { try { double n = value.has(key) ? value.get(key).getAsDouble() : 0; return Double.isFinite(n) ? n : 0; } catch (RuntimeException e) { return 0; } }
    private static boolean bool(JsonObject value, String key) { return value.has(key) && !value.get(key).isJsonNull() && value.get(key).getAsBoolean(); }
    private static void copy(JsonObject to, JsonObject from, String... keys) { for (String key : keys) if (from.has(key)) to.add(key, from.get(key).deepCopy()); }
    public static JsonObject defaultPolicy() {
        JsonObject value = new JsonObject(); value.addProperty("batchCount", 10); value.addProperty("riskDollars", 1000); value.addProperty("dailyMaxLoss", 4000);
        value.addProperty("coreTargetEnabled", false); value.addProperty("maxRiskMultipleWithExistingPosition", 1.2); value.addProperty("allowAddIfBelow", 1000); return value;
    }
    private static double partials(JsonObject direction, JsonObject policy) { double count = number(object(object(direction, "plan"), "planConfigs"), "sizingCount"); return count > 0 ? count : number(policy, "batchCount"); }
    private static JsonObject direction(String symbol, boolean isLong, TradeState state, JsonObject openTrade, JsonObject policy) {
        JsonObject saved = state.direction(symbol, isLong), result = new JsonObject(); copy(result, saved, "initialQuantity"); result.addProperty("partialsCount", partials(saved, policy));
        result.addProperty("addCount", TradeLedger.addedPartialStack(openTrade, number(saved, "initialQuantity")).size()); result.add("tradebookID", object(saved, "submitEntryResult").get("tradeBookID"));
        result.addProperty("stopTightenPhase", saved.has("stopTightenPhase") ? saved.get("stopTightenPhase").getAsString() : "idle"); return result;
    }
    private static double reference(JsonObject value, JsonObject market) {
        if (!value.has("firstTargetToAdd") || value.get("firstTargetToAdd").isJsonNull()) return 0;
        String target = value.get("firstTargetToAdd").getAsString(); if (target.equals("vwap") || target.equals("premarketHigh") || target.equals("premarketLow")) return number(market, target);
        return number(value, "firstTargetToAdd");
    }
    public static JsonObject create(String symbol, JsonObject plan, JsonObject market, JsonObject quote, JsonObject account, JsonObject ledger,
            TradeState state, JsonArray watchlist, JsonObject markets, JsonObject manual, long now, long revision, JsonObject policy) {
        JsonObject position = object(object(account, "positions"), symbol); double net = number(position, "netQuantity"); boolean isLong = net > 0;
        JsonObject active = state.direction(symbol, isLong), submission = object(active, "submitEntryResult"); JsonArray trades = array(object(ledger, "trades"), symbol); JsonObject openTrade = null;
        for (JsonElement trade : trades) if (!bool(trade.getAsJsonObject(), "isClosed")) { openTrade = trade.getAsJsonObject(); break; }
        List<JsonObject> ordered = new ArrayList<>(); for (JsonElement pair : array(object(account, "exitPairs"), symbol)) ordered.add(pair.getAsJsonObject());
        ordered.sort((a, b) -> !a.has("LIMIT") ? b.has("LIMIT") ? 1 : 0 : !b.has("LIMIT") ? -1 : bool(object(b, "LIMIT"), "isBuy")
                ? Double.compare(number(object(b, "LIMIT"), "price"), number(object(a, "LIMIT"), "price")) : Double.compare(number(object(a, "LIMIT"), "price"), number(object(b, "LIMIT"), "price")));
        JsonArray pairs = new JsonArray(); boolean largeSingle = false;
        for (int index = 0; index < ordered.size(); index++) { JsonObject pair = ordered.get(index).deepCopy(); pair.addProperty("originalPartial", index + Math.max(0, partials(active, policy) - ordered.size()) + 1); pairs.add(pair);
            if (number(object(pair, pair.has("LIMIT") ? "LIMIT" : "STOP"), "quantity") > number(submission, "totalQuantity") * 2) largeSingle = true; }
        JsonObject result = new JsonObject(); result.addProperty("symbol", symbol); result.addProperty("revision", revision); result.addProperty("netQuantity", net); result.addProperty("averagePrice", number(position, "averagePrice"));
        result.addProperty("currentPrice", number(market, "currentPrice")); result.addProperty("bid", number(quote, "bidPrice")); result.addProperty("ask", number(quote, "askPrice")); copy(result, policy, "batchCount");
        result.addProperty("splitPartials", net != 0 && (!bool(submission, "isSingleOrder") || !largeSingle)); copy(result, active, "hasValue", "entryPrice"); result.add("hasPlan", result.remove("hasValue"));
        copy(result, object(active, "plan"), "coreTarget", "coreCount"); result.addProperty("coreRuleEnabled", bool(policy, "coreTargetEnabled")); result.addProperty("rulesSupported", true); result.add("entries", array(object(account, "entryOrders"), symbol).deepCopy()); result.add("pairs", pairs);
        JsonObject context = new JsonObject(); context.add("definitions", TradingConfig.createTradebookDefinitions(plan)); context.addProperty("attendanceAllowed", true);
        context.add("activeTrade", active.deepCopy()); context.add("candles", array(market, "candles").deepCopy());
        context.add("longTrade", state.direction(symbol, true).deepCopy()); context.add("shortTrade", state.direction(symbol, false).deepCopy());
        List<String> symbols = new ArrayList<>(); double used = 0; for (JsonElement stock : watchlist) { String name = stock.getAsString(); symbols.add(name); used += Math.abs(number(object(object(account, "positions"), name), "netQuantity")) * number(object(markets, name), "currentPrice"); }
        context.addProperty("watchlistBlockReason", symbols.size() > 1 ? "more than 1 stocks in watchlist: " + String.join(", ", symbols) : ""); context.addProperty("realizedPnl", number(ledger, "realizedPnL")); copy(context, policy, "dailyMaxLoss", "riskDollars", "maxRiskMultipleWithExistingPosition", "allowAddIfBelow");
        copy(context, market, "liquidityScale", "openPrice", "vwap", "highOfDay", "lowOfDay", "premarketHigh", "premarketLow"); context.addProperty("secondsSinceMarketOpen", MarketClock.marketTime(now).minutesSinceMarketOpen * 60);
        JsonObject atr = object(plan, "atr"), analysis = object(plan, "analysis"); context.addProperty("atr", number(atr, "average")); if (atr.has("maxQuantity")) context.add("maxQuantity", atr.get("maxQuantity"));
        context.add("watchAreas", array(analysis, "watchAreas").deepCopy()); context.add("noTradeZones", array(analysis, "noTradeZones").deepCopy()); JsonArray volumes = new JsonArray();
        for (JsonElement item : array(market, "candles")) { JsonObject candle = item.getAsJsonObject(); if (!MarketClock.marketTime(candle.get("datetime").getAsLong()).isPremarket) volumes.add(candle.get("volume")); } context.add("volumes", volumes);
        for (String key : new String[]{"customEntryPrice", "customStopLong", "customStopShort", "crosshairPrice"}) context.addProperty(key, number(manual, key));
        context.addProperty("fixedQuantity", manual.has("fixedQuantity") && !manual.get("fixedQuantity").isJsonNull() ? number(manual, "fixedQuantity") : number(plan, "fixedQuantity")); context.addProperty("availableBuyingPower", number(account, "currentBalance") * 3.9 - used);
        context.addProperty("reloadIsLong", net != 0 ? isLong : trades.size() == 0 || bool(trades.get(trades.size() - 1).getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject(), "isBuy"));
        double lastExit = 0; for (int index = trades.size() - 1; index >= 0; index--) { JsonArray exits = array(trades.get(index).getAsJsonObject(), "exits"); if (exits.size() > 0) { lastExit = number(exits.get(exits.size() - 1).getAsJsonObject(), "quantity"); break; } } context.addProperty("lastExitSize", lastExit);
        context.addProperty("todayRange", Math.round(number(atr, "average") * number(atr, "mutiplier") * 100) / 100.0); context.add("longState", direction(symbol, true, state, openTrade, policy)); context.add("shortState", direction(symbol, false, state, openTrade, policy));
        if (state.symbol(symbol).has("activeBasePlan")) context.add("activeBasePlan", state.symbol(symbol).get("activeBasePlan").deepCopy()); context.addProperty("isGappedUp", number(market, "openPrice") > number(object(analysis, "gap"), "pdc"));
        context.addProperty("addTargetLong", reference(object(plan, "long"), market)); context.addProperty("addTargetShort", reference(object(plan, "short"), market)); result.add("entryContext", context); return result;
    }
}
