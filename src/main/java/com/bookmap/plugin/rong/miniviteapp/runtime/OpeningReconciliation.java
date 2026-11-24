package com.bookmap.plugin.rong.miniviteapp.runtime;

import com.google.gson.*;
import java.math.BigDecimal;
import java.util.*;

/** Never replay an ambiguous POST. Resolve it against a new, timestamped broker order. */
public final class OpeningReconciliation {
    public static final class Intent {
        final String symbol;
        final JsonObject order;
        final Set<String> previousIds;
        final long submittedAt;
        boolean unknown, observed;
        Intent(String symbol, JsonObject order, Set<String> previousIds, long submittedAt) {
            this.symbol = symbol; this.order = order.deepCopy(); this.previousIds = previousIds;
            this.submittedAt = submittedAt;
        }
    }
    private Set<String> lastIds = Collections.emptySet();
    private final List<Intent> pending = new ArrayList<>();
    private java.util.function.LongSupplier clock;
    public OpeningReconciliation() { this(System::currentTimeMillis); }
    public OpeningReconciliation(java.util.function.LongSupplier clock) { this.clock = clock; }
    public synchronized void setClock(java.util.function.LongSupplier clock) { this.clock = clock; }
    public synchronized Intent begin(String symbol, JsonObject order) {
        Intent intent = new Intent(symbol, order, new HashSet<>(lastIds), clock.getAsLong()); pending.add(intent); return intent;
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
        lastIds = ids;
        for (Intent intent : pending) {
            int matches = 0;
            for (JsonElement item : orders) {
                if (!item.isJsonObject()) continue;
                JsonObject order = item.getAsJsonObject();
                if (order.has("orderId") && !intent.previousIds.contains(order.get("orderId").getAsString())
                        && enteredAfter(order, intent.submittedAt) && contains(normalized(order), intent.order)) matches++;
            }
            intent.observed = matches == 1;
        }
        pending.removeIf(intent -> intent.unknown && intent.observed);
    }
    private static boolean enteredAfter(JsonObject order, long submittedAt) {
        if (!order.has("enteredTime")) return false;
        try {
            return com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.SchwabTime
                    .executionMillis(order.get("enteredTime").getAsString()) >= submittedAt - 2000;
        } catch (RuntimeException error) { return false; }
    }
    private static JsonObject normalized(JsonObject order) {
        JsonObject normalized = order.deepCopy();
        JsonArray legs = normalized.getAsJsonArray("orderLegCollection");
        if (legs != null && legs.size() == 1 && legs.get(0).isJsonObject() && order.has("quantity")) {
            JsonObject leg = legs.get(0).getAsJsonObject();
            if (!leg.has("quantity")) leg.add("quantity", order.get("quantity"));
        }
        return normalized;
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
