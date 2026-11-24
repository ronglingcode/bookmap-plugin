package com.bookmap.plugin.rong.miniviteapp.runtime;

import com.google.gson.*;
import java.math.BigDecimal;
import java.util.*;

/** Never replay an ambiguous opening POST. Resolve it against a newly observed broker order. */
public final class OpeningReconciliation {
    public static final class Intent {
        final String symbol;
        final JsonObject order;
        final Set<String> previousIds;
        boolean unknown, observed;
        Intent(String symbol, JsonObject order, Set<String> previousIds) {
            this.symbol = symbol; this.order = order.deepCopy(); this.previousIds = previousIds;
        }
    }
    private Set<String> lastIds = Collections.emptySet();
    private JsonArray lastOrders = new JsonArray();
    private final List<Intent> pending = new ArrayList<>();
    public synchronized Intent begin(String symbol, JsonObject order) {
        Intent intent = new Intent(symbol, order, new HashSet<>(lastIds)); pending.add(intent); return intent;
    }
    public synchronized void complete(Intent intent, boolean unknown) {
        intent.unknown = unknown;
        if (!unknown || intent.observed) pending.remove(intent);
    }
    public synchronized boolean unresolved(String symbol) {
        return pending.stream().anyMatch(intent -> intent.symbol.equals(symbol) && intent.unknown);
    }
    public synchronized void observe(JsonArray orders) {
        Set<String> ids = new HashSet<>();
        for (JsonElement item : orders) if (item.isJsonObject() && item.getAsJsonObject().has("orderId"))
            ids.add(item.getAsJsonObject().get("orderId").getAsString());
        lastIds = ids; lastOrders = orders.deepCopy();
        for (Intent intent : pending) {
            int matches = 0;
            for (JsonElement item : lastOrders) {
                if (!item.isJsonObject()) continue;
                JsonObject order = item.getAsJsonObject();
                if (order.has("orderId") && !intent.previousIds.contains(order.get("orderId").getAsString())
                        && contains(order, intent.order)) matches++;
            }
            intent.observed = matches == 1;
        }
        pending.removeIf(intent -> intent.unknown && intent.observed);
    }
    private static boolean contains(JsonElement actual, JsonElement expected) {
        if (actual == null) return false;
        if (expected.isJsonObject()) {
            if (!actual.isJsonObject()) return false;
            for (Map.Entry<String, JsonElement> field : expected.getAsJsonObject().entrySet())
                if (!contains(actual.getAsJsonObject().get(field.getKey()), field.getValue())) return false;
            return true;
        }
        if (expected.isJsonArray()) {
            if (!actual.isJsonArray() || actual.getAsJsonArray().size() != expected.getAsJsonArray().size()) return false;
            for (int i = 0; i < expected.getAsJsonArray().size(); i++)
                if (!contains(actual.getAsJsonArray().get(i), expected.getAsJsonArray().get(i))) return false;
            return true;
        }
        if (expected.equals(actual)) return true;
        if (!expected.isJsonPrimitive() || !actual.isJsonPrimitive()) return false;
        try { return new BigDecimal(actual.getAsString()).compareTo(new BigDecimal(expected.getAsString())) == 0; }
        catch (NumberFormatException error) { return false; }
    }
}
