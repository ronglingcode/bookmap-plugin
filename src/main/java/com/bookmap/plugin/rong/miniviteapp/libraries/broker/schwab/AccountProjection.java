package com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab;

import com.bookmap.plugin.rong.miniviteapp.core.marketdata.MarketClock;
import com.google.gson.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/** Explicit JSON models mirror TS projectAccount and survive obfuscation. */
public final class AccountProjection {
    private static final Set<String> TERMINAL = Set.of("FILLED", "CANCELED", "REPLACED", "REJECTED", "EXPIRED");
    private static final Set<String> WORKING = Set.of("PENDING_ACTIVATION", "QUEUED", "WORKING", "AWAITING_PARENT_ORDER", "PARTIALLY_FILLED");
    private AccountProjection() { }
    private static String string(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : ""; }
    private static double number(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsDouble() : 0; }
    private static JsonArray array(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray(); }
    private static JsonObject leg(JsonObject order) { JsonArray legs = array(order, "orderLegCollection"); return legs.size() > 0 ? legs.get(0).getAsJsonObject() : null; }
    public static String orderSymbol(JsonObject order) {
        JsonObject leg = leg(order);
        if (leg != null && leg.has("instrument")) return string(leg.getAsJsonObject("instrument"), "symbol");
        JsonArray children = array(order, "childOrderStrategies"); return children.size() > 0 ? orderSymbol(children.get(0).getAsJsonObject()) : "";
    }
    private static JsonObject orderModel(JsonObject order, ToDoubleFunction<String> currentPrice) {
        JsonObject leg = leg(order); String symbol = orderSymbol(order);
        if (leg == null || symbol.isEmpty()) throw new IllegalArgumentException("Schwab single equity order missing leg/symbol");
        JsonObject model = new JsonObject(); model.addProperty("symbol", symbol);
        model.addProperty("orderID", string(order, order.has("orderId") ? "orderId" : "orderID"));
        String type = string(order, "orderType"); model.addProperty("orderType", type);
        model.addProperty("quantity", Math.max(0, number(order.has("quantity") ? order : leg, "quantity") - number(order, "filledQuantity")));
        model.addProperty("isBuy", Set.of("BUY", "BUY_TO_COVER").contains(string(leg, "instruction")));
        model.addProperty("positionEffectIsOpen", string(leg, "positionEffect").equals("OPENING"));
        model.addProperty("price", type.equals("STOP") ? number(order, "stopPrice") : type.equals("LIMIT") ? number(order, "price") : currentPrice.applyAsDouble(symbol));
        model.add("rawOrder", order.deepCopy()); return model;
    }
    private static List<JsonObject> workingChildren(JsonObject order) {
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement value : array(order, "childOrderStrategies")) {
            JsonObject child = value.getAsJsonObject(); String type = string(child, "orderStrategyType");
            if (type.equals("OCO")) result.addAll(workingChildren(child));
            else if (type.equals("SINGLE") && WORKING.contains(string(child, "status"))) result.add(child);
        }
        return result;
    }
    private static JsonObject exitPair(JsonObject oco, JsonObject parent, String source, ToDoubleFunction<String> currentPrice) {
        JsonObject result = new JsonObject(); result.addProperty("symbol", orderSymbol(parent)); result.addProperty("source", source); result.addProperty("parentOrderID", string(oco, "orderId"));
        for (JsonObject child : workingChildren(oco)) {
            String type = string(child, "orderType"); if (type.equals("STOP") || type.equals("LIMIT")) result.add(type, orderModel(child, currentPrice));
        }
        if (!result.has("STOP") && !result.has("LIMIT")) return null;
        if (result.has("STOP") && result.has("LIMIT")) {
            double quantity = Math.min(number(result.getAsJsonObject("STOP"), "quantity"), number(result.getAsJsonObject("LIMIT"), "quantity"));
            result.getAsJsonObject("STOP").addProperty("quantity", quantity); result.getAsJsonObject("LIMIT").addProperty("quantity", quantity);
        }
        return number(result.getAsJsonObject(result.has("STOP") ? "STOP" : "LIMIT"), "quantity") > 0 ? result : null;
    }
    private static void add(JsonObject map, String symbol, JsonObject value) {
        if (!map.has(symbol)) map.add(symbol, new JsonArray()); map.getAsJsonArray(symbol).add(value);
    }
    private static void visitFills(JsonObject order, String date, JsonObject executions) {
        JsonObject leg = leg(order); String symbol = orderSymbol(order);
        if (leg != null && (string(leg, "orderLegType").equals("EQUITY") || leg.has("instrument") && string(leg.getAsJsonObject("instrument"), "assetType").equals("EQUITY"))) {
            for (JsonElement value : array(order, "orderActivityCollection")) {
                JsonObject activity = value.getAsJsonObject();
                if (!string(activity, "activityType").equals("EXECUTION") || !string(activity, "executionType").equals("FILL")) continue;
                double quantity = 0, dollars = 0; JsonObject first = null;
                for (JsonElement item : array(activity, "executionLegs")) {
                    JsonObject fill = item.getAsJsonObject();
                    if (fill.has("legId") && leg.has("legId") && !fill.get("legId").equals(leg.get("legId"))) continue;
                    if (first == null) first = fill; quantity += number(fill, "quantity"); dollars += number(fill, "quantity") * number(fill, "price");
                }
                if (quantity == 0) continue;
                long timestamp;
                try { timestamp = Instant.parse(string(first, "time")).toEpochMilli(); } catch (RuntimeException error) { continue; }
                if (!MarketClock.marketTime(timestamp).date.equals(date)) continue;
                JsonObject fill = new JsonObject(); fill.addProperty("symbol", symbol); fill.addProperty("orderID", string(order, "orderId"));
                fill.addProperty("timestamp", timestamp); fill.addProperty("quantity", quantity); fill.addProperty("price", dollars / quantity);
                fill.addProperty("isBuy", Set.of("BUY", "BUY_TO_COVER").contains(string(leg, "instruction"))); fill.addProperty("positionEffectIsOpen", string(leg, "positionEffect").equals("OPENING")); add(executions, symbol, fill);
            }
        }
        for (JsonElement child : array(order, "childOrderStrategies")) visitFills(child.getAsJsonObject(), date, executions);
    }
    public static JsonObject projectAccount(JsonObject account, JsonArray orders, String date, ToDoubleFunction<String> currentPrice) {
        JsonObject positions = new JsonObject(), entries = new JsonObject(), pairs = new JsonObject(), executions = new JsonObject();
        for (JsonElement value : array(account, "positions")) {
            JsonObject position = value.getAsJsonObject().deepCopy();
            String symbol = position.has("instrument") ? string(position.getAsJsonObject("instrument"), "symbol") : "";
            if (symbol.isEmpty()) throw new IllegalArgumentException("Schwab position missing symbol");
            position.addProperty("symbol", symbol); position.addProperty("netQuantity", number(position, "longQuantity") - number(position, "shortQuantity")); positions.add(symbol, position);
        }
        for (JsonElement value : orders) {
            JsonObject order = value.getAsJsonObject(); visitFills(order, date, executions); String symbol = orderSymbol(order);
            if (symbol.isEmpty()) continue;
            String type = string(order, "orderStrategyType"); JsonObject leg = leg(order);
            if (type.equals("SINGLE") && !TERMINAL.contains(string(order, "status")) && leg != null && string(leg, "positionEffect").equals("OPENING")
                    || type.equals("TRIGGER") && order.has("cancelable") && order.get("cancelable").getAsBoolean()) {
                JsonObject model = orderModel(order, currentPrice);
                for (JsonElement childValue : array(order, "childOrderStrategies")) {
                    JsonObject child = childValue.getAsJsonObject(); if (!string(child, "orderStrategyType").equals("OCO")) continue;
                    JsonObject pair = exitPair(child, order, "OTO", currentPrice);
                    if (pair != null) { if (pair.has("STOP")) model.add("exitStopPrice", pair.getAsJsonObject("STOP").get("price")); if (pair.has("LIMIT")) model.add("exitLimitPrice", pair.getAsJsonObject("LIMIT").get("price")); }
                    break;
                }
                if (number(model, "quantity") > 0) add(entries, symbol, model);
            }
            if (type.equals("OCO")) { JsonObject pair = exitPair(order, order, "OCO", currentPrice); if (pair != null) add(pairs, symbol, pair); }
            else if (type.equals("TRIGGER") && (string(order, "status").equals("FILLED") || number(order, "filledQuantity") > 0)) {
                for (JsonElement childValue : array(order, "childOrderStrategies")) {
                    JsonObject child = childValue.getAsJsonObject(); if (string(child, "orderStrategyType").equals("OCO")) { JsonObject pair = exitPair(child, order, "OTO", currentPrice); if (pair != null) add(pairs, symbol, pair); }
                }
            }
        }
        for (String symbol : new ArrayList<>(executions.keySet())) {
            List<JsonObject> list = new ArrayList<>(); executions.getAsJsonArray(symbol).forEach(value -> list.add(value.getAsJsonObject()));
            list.sort(Comparator.comparingLong(fill -> fill.get("timestamp").getAsLong())); JsonArray sorted = new JsonArray(); list.forEach(sorted::add); executions.add(symbol, sorted);
        }
        if (!account.has("currentBalances") || !account.getAsJsonObject("currentBalances").has("liquidationValue")) throw new IllegalArgumentException("Schwab account missing liquidationValue");
        double balance = number(account.getAsJsonObject("currentBalances"), "liquidationValue"); if (!Double.isFinite(balance)) throw new IllegalArgumentException("Schwab account missing liquidationValue");
        JsonObject result = new JsonObject(); result.add("positions", positions); result.add("entryOrders", entries); result.add("exitPairs", pairs); result.add("executions", executions);
        result.addProperty("currentBalance", balance); result.add("rawOrders", orders.deepCopy()); return result;
    }
}
