package com.bookmap.plugin.rong.miniviteapp.core.account;

import com.google.gson.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Same position-based fill grouping, weighted-entry P&L and add stack as TS TradeLedger. */
public final class TradeLedger {
    private TradeLedger() { }
    private static double number(JsonObject json, String key) { return json.get(key).getAsDouble(); }
    private static List<JsonObject> sorted(JsonArray values) { List<JsonObject> list = new ArrayList<>(); values.forEach(value -> list.add(value.getAsJsonObject())); list.sort(Comparator.comparingLong(fill -> fill.get("timestamp").getAsLong())); return list; }
    public static JsonArray groupTradeExecutions(String symbol, JsonArray fills) {
        JsonArray trades = new JsonArray(); double net = 0;
        for (JsonObject fill : sorted(fills)) {
            boolean buy = fill.get("isBuy").getAsBoolean(); double quantity = number(fill, "quantity");
            if (net != 0 && (net > 0) != buy) {
                double closing = Math.min(quantity, Math.abs(net)); JsonObject exit = fill.deepCopy(); exit.addProperty("quantity", closing); exit.addProperty("positionEffectIsOpen", false);
                trades.get(trades.size() - 1).getAsJsonObject().getAsJsonArray("exits").add(exit); net += buy ? closing : -closing; quantity -= closing;
            }
            if (quantity > 0) {
                JsonObject entry = fill.deepCopy(); entry.addProperty("quantity", quantity); entry.addProperty("positionEffectIsOpen", true);
                if (net == 0) {
                    JsonObject trade = new JsonObject(); trade.addProperty("symbol", symbol); trade.add("entries", new JsonArray()); trade.add("exits", new JsonArray()); trade.addProperty("realizedPnL", 0); trade.addProperty("isLong", buy); trade.addProperty("isClosed", false); trades.add(trade);
                }
                trades.get(trades.size() - 1).getAsJsonObject().getAsJsonArray("entries").add(entry); net += buy ? quantity : -quantity;
            }
        }
        for (JsonElement value : trades) {
            JsonObject trade = value.getAsJsonObject(); double quantity = 0, dollars = 0, exited = 0, pnl = 0;
            Map<Long, List<JsonObject>> minutes = new LinkedHashMap<>();
            for (JsonElement element : trade.getAsJsonArray("entries")) {
                JsonObject entry = element.getAsJsonObject(); quantity += number(entry, "quantity"); dollars += number(entry, "quantity") * number(entry, "price");
                minutes.computeIfAbsent(entry.get("timestamp").getAsLong() / 60000, key -> new ArrayList<>()).add(entry);
            }
            double average = dollars / quantity; boolean isLong = trade.get("isLong").getAsBoolean();
            for (JsonElement element : trade.getAsJsonArray("exits")) {
                JsonObject exit = element.getAsJsonObject(); exited += number(exit, "quantity"); pnl += (isLong ? number(exit, "price") - average : average - number(exit, "price")) * number(exit, "quantity");
            }
            trade.addProperty("realizedPnL", pnl); trade.addProperty("isClosed", quantity == exited);
            JsonArray aggregated = new JsonArray();
            for (List<JsonObject> entries : minutes.values()) {
                double total = 0, amount = 0; for (JsonObject entry : entries) { total += number(entry, "quantity"); amount += number(entry, "quantity") * number(entry, "price"); }
                JsonObject entry = entries.get(0).deepCopy(); entry.addProperty("quantity", total); entry.addProperty("price", amount / total); aggregated.add(entry);
            }
            trade.add("entries", aggregated);
        }
        return trades;
    }
    public static JsonObject projectTradeLedger(JsonObject executions, double dailyMaxLoss) {
        JsonObject trades = new JsonObject(); int count = 0, nonBreakeven = 0; double pnl = 0;
        for (String symbol : executions.keySet()) {
            JsonArray grouped = groupTradeExecutions(symbol, executions.getAsJsonArray(symbol)); trades.add(symbol, grouped);
            for (JsonElement element : grouped) { count++; double profit = number(element.getAsJsonObject(), "realizedPnL"); pnl += profit; if (Math.abs(profit) > dailyMaxLoss * 0.05) nonBreakeven++; }
        }
        JsonObject result = new JsonObject(); result.add("trades", trades); result.addProperty("tradesCount", count); result.addProperty("nonBreakevenTradesCount", nonBreakeven); result.addProperty("realizedPnL", pnl); return result;
    }
    public static JsonArray addedPartialStack(JsonObject trade, double initialQuantity) {
        JsonArray fills = new JsonArray(); if (trade != null) { fills.addAll(trade.getAsJsonArray("entries")); fills.addAll(trade.getAsJsonArray("exits")); }
        JsonArray stack = new JsonArray(); double quantity = 0; boolean hasExit = false;
        for (JsonObject fill : sorted(fills)) {
            if (fill.get("positionEffectIsOpen").getAsBoolean()) { quantity += number(fill, "quantity"); if (quantity > initialQuantity || hasExit) stack.add(fill.get("price")); }
            else { hasExit = true; quantity -= number(fill, "quantity"); if (stack.size() > 0) stack.remove(stack.size() - 1); }
        }
        return stack;
    }
}
