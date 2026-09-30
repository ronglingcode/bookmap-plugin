package com.bookmap.plugin.rong.miniviteapp.models;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Explicit wire parsing survives release obfuscation; never deserialize fields reflectively. */
public final class Models {
    private Models() { }
    public static String string(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }
    public static double number(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsDouble() : Double.NaN;
    }
    public static boolean bool(JsonObject json, String key) {
        return json.has(key) && json.get(key).getAsBoolean();
    }
    public static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
    public static boolean positive(double value) { return Double.isFinite(value) && value > 0; }

    public static final class Order {
        public final String id, symbol, type;
        public final double quantity, price, exitStopPrice;
        public final boolean isBuy;
        public Order(JsonObject json, String symbol) {
            this.symbol = symbol;
            id = string(json, "orderID");
            type = string(json, "orderType");
            quantity = number(json, "quantity");
            price = number(json, "price");
            exitStopPrice = number(json, "exitStopPrice");
            isBuy = bool(json, "isBuy");
        }
    }
    public static final class ExitPair {
        public final Order limit, stop;
        public final int originalPartial;
        public ExitPair(JsonObject json, String symbol) {
            limit = json.has("LIMIT") ? new Order(json.getAsJsonObject("LIMIT"), symbol) : null;
            stop = json.has("STOP") ? new Order(json.getAsJsonObject("STOP"), symbol) : null;
            originalPartial = (int) number(json, "originalPartial");
            require(limit == null || stop == null ||
                    (limit.quantity == stop.quantity && limit.isBuy == stop.isBuy), "exit legs disagree");
        }
        public Order marketLeg() { return limit != null ? limit : stop; }
        public double quantity() { return marketLeg().quantity; }
    }
    public static final class Snapshot {
        public final String symbol;
        public final long revision;
        public final double netQuantity, currentPrice, bid, ask, entryPrice, coreTarget, coreCount, averagePrice;
        public final int batchCount;
        public final boolean splitPartials, hasPlan, coreRuleEnabled, rulesSupported;
        public final List<Order> entries;
        public final List<ExitPair> pairs;
        public final JsonObject entryContext;
        public Snapshot(JsonObject json) {
            symbol = string(json, "symbol");
            revision = (long) number(json, "revision");
            netQuantity = number(json, "netQuantity");
            averagePrice = number(json, "averagePrice");
            currentPrice = number(json, "currentPrice");
            bid = number(json, "bid"); ask = number(json, "ask");
            entryPrice = number(json, "entryPrice"); coreTarget = number(json, "coreTarget");
            coreCount = number(json, "coreCount"); batchCount = (int) number(json, "batchCount");
            splitPartials = bool(json, "splitPartials"); hasPlan = bool(json, "hasPlan");
            coreRuleEnabled = bool(json, "coreRuleEnabled"); rulesSupported = bool(json, "rulesSupported");
            List<Order> entryList = new ArrayList<>();
            for (var entry : json.getAsJsonArray("entries")) entryList.add(new Order(entry.getAsJsonObject(), symbol));
            entries = Collections.unmodifiableList(entryList);
            List<ExitPair> pairList = new ArrayList<>();
            for (var pair : json.getAsJsonArray("pairs")) pairList.add(new ExitPair(pair.getAsJsonObject(), symbol));
            pairs = Collections.unmodifiableList(pairList);
            entryContext = json.has("entryContext") && !json.get("entryContext").isJsonNull() ? json.getAsJsonObject("entryContext").deepCopy() : null;
        }
        /** Overlay streaming prices without replacing account/plan inputs or an in-flight action. */
        public Snapshot(Snapshot state, JsonObject marketData) {
            symbol = state.symbol; revision = state.revision; netQuantity = state.netQuantity;
            averagePrice = state.averagePrice;
            currentPrice = number(marketData, "currentPrice");
            bid = number(marketData, "bid"); ask = number(marketData, "ask");
            entryPrice = state.entryPrice; coreTarget = state.coreTarget; coreCount = state.coreCount;
            batchCount = state.batchCount; splitPartials = state.splitPartials; hasPlan = state.hasPlan;
            coreRuleEnabled = state.coreRuleEnabled; rulesSupported = state.rulesSupported;
            entries = state.entries; pairs = state.pairs;
            entryContext = state.entryContext == null ? null : state.entryContext.deepCopy();
            if (entryContext != null) {
                entryContext.add("highOfDay", marketData.get("highOfDay"));
                entryContext.add("lowOfDay", marketData.get("lowOfDay"));
            }
        }
    }
    public static final class Request {
        public final String method, orderId;
        public final JsonObject body;
        public final Order original;
        public long delayBeforeMs;
        public Request(String method, Order original, JsonObject body) {
            this.method = method; this.original = original;
            this.orderId = original == null ? "" : original.id; this.body = body;
        }
        public JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("method", method); result.addProperty("orderId", orderId);
            if (body != null) result.add("body", body);
            return result;
        }
    }
    public static final class Plan {
        public final String action;
        public final List<Request> requests = new ArrayList<>();
        public boolean clearPending;
        public JsonObject entry;
        public boolean experimental, requireFlatEntry;
        public Plan(String action) { this.action = action; }
        public JsonArray toJson() {
            JsonArray result = new JsonArray();
            requests.forEach(request -> result.add(request.toJson())); return result;
        }
    }
}
