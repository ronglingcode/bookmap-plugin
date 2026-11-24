package com.bookmap.plugin.rong.miniviteapp.runtime;

import com.google.gson.*;
import java.util.*;
import static com.bookmap.plugin.rong.miniviteapp.models.DomainJson.*;

/** Stable per-symbol chart numbers. Repricing transfers the number to the replacement broker ID. */
public final class PremarketOrderIndex {
    private final Map<String, Integer> numbers = new HashMap<>();
    private final Map<String, String> symbols = new HashMap<>();
    private final Map<String, JsonObject> active = new LinkedHashMap<>();
    private final Set<String> retired = new HashSet<>();
    private final Set<String> localPending = new HashSet<>(), reserved = new HashSet<>();
    public static boolean isManualLimit(JsonObject order) {
        return string(order, "orderStrategyType").equals("SINGLE") && string(order, "orderType").equals("LIMIT")
                && Set.of("AM", "PM", "SEAMLESS").contains(string(order, "session"))
                && array(order, "orderLegCollection").size() == 1;
    }
    public static boolean live(JsonObject order) { return !Set.of("CANCELED", "FILLED", "REPLACED", "REJECTED", "EXPIRED").contains(string(order, "status")); }
    public static String symbol(JsonObject order) {
        return array(order, "orderLegCollection").isEmpty() ? "" : string(object(order.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject(), "instrument"), "symbol");
    }
    public synchronized void observe(JsonArray orders) {
        List<JsonObject> sorted = new ArrayList<>();
        for (JsonElement item : orders) gather(item.getAsJsonObject(), sorted);
        sorted.sort(Comparator.comparing(order -> string(order, "enteredTime") + ":" + string(order, "orderId")));
        Map<String, JsonObject> pending = new LinkedHashMap<>();
        for (String id : localPending) if (active.containsKey(id)) pending.put(id, active.get(id));
        for (JsonObject raw : sorted) {
            String id = string(raw, "orderId"); localPending.remove(id); pending.remove(id);
            if (!live(raw)) retired.add(id);
        }
        active.clear(); active.putAll(pending);
        // Preserve occupied numbers before allocating any newly observed order's free slot.
        for (JsonObject raw : sorted) if (live(raw) && numbers.containsKey(string(raw, "orderId")) && !retired.contains(string(raw, "orderId"))) active.put(string(raw, "orderId"), raw.deepCopy());
        sorted.forEach(this::accept);
    }
    private void gather(JsonObject order, List<JsonObject> result) {
        if (isManualLimit(order)) result.add(order);
        for (JsonElement item : array(order, "childOrderStrategies")) gather(item.getAsJsonObject(), result);
    }
    public synchronized void accept(JsonObject raw) {
        String id = string(raw, "orderId"), symbol = symbol(raw);
        if (retired.contains(id) || !id.matches("[0-9]+") || symbol.isEmpty() || !isManualLimit(raw) || !live(raw)) return;
        symbols.put(id, symbol);
        if (!numbers.containsKey(id)) {
            Set<Integer> occupied = new HashSet<>();
            for (String existing : active.keySet()) if (symbol.equals(symbols.get(existing))) occupied.add(numbers.get(existing));
            for (String existing : reserved) if (symbol.equals(symbols.get(existing))) occupied.add(numbers.get(existing));
            int index = 1; while (occupied.contains(index)) index++; numbers.put(id, index);
        }
        active.put(id, raw.deepCopy());
    }
    public synchronized void acceptLocal(JsonObject raw) { accept(raw); localPending.add(string(raw, "orderId")); }
    public synchronized void reserve(String id) { if (numbers.containsKey(id)) reserved.add(id); }
    public synchronized void release(String symbol) { reserved.removeIf(id -> symbol.equals(symbols.get(id))); }
    public synchronized void replace(String oldId, JsonObject replacement) {
        Integer number = numbers.get(oldId); retired.add(oldId); active.remove(oldId);
        localPending.remove(oldId); reserved.remove(oldId);
        if (number != null) numbers.put(string(replacement, "orderId"), number);
        acceptLocal(replacement);
    }
    public synchronized String id(String symbol, int number) {
        for (Map.Entry<String, JsonObject> entry : active.entrySet())
            if (symbol.equals(symbol(entry.getValue())) && numbers.get(entry.getKey()) == number) return entry.getKey();
        return "";
    }
    public synchronized JsonArray snapshot(String symbol) {
        List<JsonObject> values = new ArrayList<>();
        for (var entry : active.entrySet()) {
            JsonObject raw = entry.getValue(); if (!symbol.equals(symbol(raw))) continue;
            JsonObject leg = raw.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject();
            JsonObject value = new JsonObject(); value.addProperty("orderID", entry.getKey()); value.addProperty("orderType", "LIMIT");
            value.addProperty("symbol", symbol); value.addProperty("price", number(raw, "price"));
            value.addProperty("quantity", Math.max(0, number(raw, "quantity") - number(raw, "filledQuantity")));
            value.addProperty("isBuy", Set.of("BUY", "BUY_TO_COVER").contains(string(leg, "instruction")));
            value.addProperty("pairIndex", numbers.get(entry.getKey())); value.addProperty("role", "PREMARKET");
            value.addProperty("source", "PREMARKET"); values.add(value);
        }
        values.sort(Comparator.comparingDouble(value -> number(value, "pairIndex")));
        JsonArray result = new JsonArray(); values.forEach(result::add); return result;
    }
}
