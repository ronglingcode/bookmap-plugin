package com.bookmap.plugin.rong;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Loopback projections for Cairo Live. Never accepts trading commands or credentials. */
final class CairoLiveFeed {
    private final String source = UUID.randomUUID().toString();
    private final Consumer<String> send;
    private final Map<String, JsonObject> charts = new LinkedHashMap<>();
    private final Map<String, JsonObject> accounts = new LinkedHashMap<>();
    private final Map<String, JsonObject> replay = new LinkedHashMap<>();
    private final Map<String, JsonObject> prices = new LinkedHashMap<>();
    private final Map<String, Long> tradeSequences = new LinkedHashMap<>();
    private final Map<String, Long> resetWatermarks = new LinkedHashMap<>();
    private long sequence;

    CairoLiveFeed(Consumer<String> send) { this.send = send; }

    synchronized void snapshot(Consumer<String> client) {
        for (Map<String, JsonObject> entries : java.util.List.of(charts, accounts, replay, prices)) {
            for (JsonObject entry : entries.values()) {
                JsonObject copy = entry.deepCopy(); copy.addProperty("delivery", "snapshot");
                client.accept(copy.toString());
            }
        }
    }

    synchronized void chart(String symbol, String mode, boolean attached) {
        JsonObject event = envelope("cairo_chart", symbol, mode);
        event.addProperty("attached", attached);
        if (attached) charts.put(symbol, event);
        else { charts.remove(symbol); prices.remove(symbol); replay.remove(symbol); tradeSequences.remove(symbol); resetWatermarks.remove(symbol); }
        send.accept(event.toString());
    }

    synchronized JsonObject makeTrade(String symbol, String mode, double price, long timestampNs) {
        if (!charts.containsKey(symbol) || !Double.isFinite(price) || price <= 0 || timestampNs <= 0) return null;
        JsonObject event = envelope("cairo_trade", symbol, mode);
        event.addProperty("tradeSequence", tradeSequences.merge(symbol, 1L, Long::sum));
        event.addProperty("price", price);
        event.addProperty("eventTime", Long.toString(timestampNs));
        return event;
    }

    synchronized void trade(JsonObject event) {
        String symbol = event.get("symbol").getAsString();
        JsonObject chart = charts.get(symbol);
        if (chart == null || event.get("sequence").getAsLong() <= Math.max(chart.get("sequence").getAsLong(), resetWatermarks.getOrDefault(symbol, 0L))) return;
        JsonObject previous = prices.get(symbol);
        if (previous != null && previous.get("sequence").getAsLong() >= event.get("sequence").getAsLong()) return;
        prices.put(symbol, event);
        send.accept(event.toString());
    }

    synchronized void replay(JsonObject event) {
        String symbol = event.get("symbol").getAsString();
        if (event.get("type").getAsString().equals("cairo_replay_reset")) {
            prices.remove(symbol);
            resetWatermarks.put(symbol, sequence);
        }
        replay.put(symbol, event.deepCopy());
        send.accept(event.toString());
    }

    synchronized void account(JsonObject view, long asOf) {
        if (asOf <= 0) return;
        String symbol = view.get("symbol").getAsString();
        JsonObject previous = accounts.get(symbol);
        if (previous != null && previous.get("asOf").getAsLong() >= asOf) return;
        JsonObject event = envelope("cairo_account", symbol, "live");
        event.addProperty("asOf", asOf);
        JsonObject position = view.getAsJsonObject("position");
        event.addProperty("positionQuantity", position == null ? 0 : position.get("netQuantity").getAsDouble());
        if (position != null) event.add("positionAveragePrice", position.get("averagePrice"));
        JsonArray stops = new JsonArray();
        for (JsonElement value : view.getAsJsonArray("openOrders")) {
            JsonObject order = value.getAsJsonObject();
            if ("STOP".equals(order.get("role").getAsString())) stops.add(project(order, "price", "quantity"));
        }
        event.add("workingStops", stops);
        JsonArray fills = new JsonArray();
        for (JsonElement value : view.getAsJsonArray("executions")) {
            JsonObject fill = project(value.getAsJsonObject(), "timestamp", "price", "quantity", "isBuy", "positionEffectIsOpen");
            JsonObject raw = value.getAsJsonObject();
            String identity = raw.get("orderID").getAsString() + ":" + fill;
            fill.addProperty("executionId", UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
            fills.add(fill);
        }
        event.add("executions", fills);
        accounts.put(symbol, event);
        send.accept(event.toString());
    }

    private JsonObject envelope(String type, String symbol, String mode) {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        event.addProperty("version", 1);
        event.addProperty("symbol", symbol);
        event.addProperty("mode", mode);
        event.addProperty("sourceInstanceId", source);
        event.addProperty("epoch", 1);
        event.addProperty("sequence", ++sequence);
        event.addProperty("delivery", "stream");
        return event;
    }

    private static JsonObject project(JsonObject source, String... keys) {
        JsonObject result = new JsonObject();
        for (String key : keys) if (source.has(key)) result.add(key, source.get(key));
        return result;
    }
}
