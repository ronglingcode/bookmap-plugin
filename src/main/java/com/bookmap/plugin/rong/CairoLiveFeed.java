package com.bookmap.plugin.rong;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.bookmap.plugin.rong.miniviteapp.core.marketdata.MarketClock;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

/** Loopback projections for Cairo Live. Never accepts trading commands or credentials. */
final class CairoLiveFeed {
    private static final long MINUTE = 60_000L;
    private static final class Minute {
        long first, last, volume;
        double open, high, low, close;

        Minute(long time, double price) {
            first = last = time;
            open = high = low = close = price;
        }

        void trade(long time, double price, int size) {
            if (time < first) { first = time; open = price; }
            if (time >= last) { last = time; close = price; }
            high = Math.max(high, price);
            low = Math.min(low, price);
            volume += size;
        }
    }

    private static final class Chart {
        String date;
        long asOf;
        final TreeMap<Long, Minute> minutes = new TreeMap<>();
    }

    private final String source = UUID.randomUUID().toString();
    private final Consumer<String> send;
    private final Map<String, JsonObject> charts = new LinkedHashMap<>();
    private final Map<String, JsonObject> accounts = new LinkedHashMap<>();
    private final Map<String, Deque<JsonObject>> replay = new LinkedHashMap<>();
    private final Map<String, JsonObject> prices = new LinkedHashMap<>();
    private final Map<String, Chart> candles = new LinkedHashMap<>();
    private final Map<String, Long> tradeSequences = new LinkedHashMap<>();
    private final Map<String, Long> resetWatermarks = new LinkedHashMap<>();
    private long sequence;

    CairoLiveFeed(Consumer<String> send) { this.send = send; }

    synchronized void snapshot(Consumer<String> client) {
        charts.values().forEach(entry -> sendSnapshot(client, entry));
        accounts.values().forEach(entry -> sendSnapshot(client, entry));
        replay.values().forEach(history -> history.forEach(entry -> sendSnapshot(client, entry)));
        prices.values().forEach(entry -> sendSnapshot(client, entry));
    }

    private static void sendSnapshot(Consumer<String> client, JsonObject entry) {
        JsonObject copy = entry.deepCopy();
        copy.addProperty("delivery", "snapshot");
        client.accept(copy.toString());
    }

    synchronized void chart(String symbol, String mode, boolean attached) {
        JsonObject event = envelope("cairo_chart", symbol, mode);
        event.addProperty("attached", attached);
        if (attached) charts.put(symbol, event);
        else { charts.remove(symbol); prices.remove(symbol); replay.remove(symbol); tradeSequences.remove(symbol); resetWatermarks.remove(symbol); candles.remove(symbol); }
        send.accept(event.toString());
    }

    synchronized JsonObject makeTrade(String symbol, String mode, double price, long timestampNs, int size) {
        JsonObject attached = charts.get(symbol);
        if (attached == null || !mode.equals(attached.get("mode").getAsString()) ||
                !Double.isFinite(price) || price <= 0 || timestampNs <= 0) return null;
        if (size > 0) {
            long time = timestampNs / 1_000_000L;
            Chart chart = candles.computeIfAbsent(symbol, ignored -> new Chart());
            long bucket = time / MINUTE * MINUTE;
            // Resolve the session date on minute transitions rather than on every print.
            if (chart.minutes.isEmpty() || bucket > chart.minutes.lastKey() || bucket < chart.minutes.firstKey()) {
                String date = MarketClock.marketTime(time).date;
                if (!date.equals(chart.date)) {
                    if (chart.date != null && date.compareTo(chart.date) < 0) return null;
                    chart.date = date;
                    chart.minutes.clear();
                    chart.asOf = 0;
                }
            }
            chart.minutes.computeIfAbsent(bucket, ignored -> new Minute(timestampNs, price)).trade(timestampNs, price, size);
            chart.asOf = Math.max(chart.asOf, time);
        }
        JsonObject event = envelope("cairo_trade", symbol, mode);
        event.addProperty("tradeSequence", tradeSequences.merge(symbol, 1L, Long::sum));
        event.addProperty("price", price);
        event.addProperty("eventTime", Long.toString(timestampNs));
        return event;
    }

    synchronized void minuteBars(JsonObject response, String symbol, String mode) {
        Chart chart = candles.get(symbol);
        JsonObject attached = charts.get(symbol);
        if (chart == null || attached == null || !mode.equals(attached.get("mode").getAsString())) return;
        response.addProperty("sessionDate", chart.date);
        response.addProperty("asOf", chart.asOf);
        response.addProperty("source", "Bookmap observed trades");
        JsonArray bars = new JsonArray();
        chart.minutes.forEach((time, bar) -> {
            JsonArray row = new JsonArray();
            row.add(time); row.add(bar.open); row.add(bar.high); row.add(bar.low); row.add(bar.close); row.add(bar.volume);
            bars.add(row);
        });
        response.add("bars", bars);
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
        Deque<JsonObject> history = replay.computeIfAbsent(symbol, ignored -> new ArrayDeque<>());
        if (event.get("type").getAsString().equals("cairo_replay_reset")) {
            history.clear();
            prices.remove(symbol);
            candles.remove(symbol);
            resetWatermarks.put(symbol, sequence);
        }
        history.addLast(event.deepCopy());
        if (history.size() > 1000) history.removeFirst();
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
            fill.addProperty("orderId", UUID.nameUUIDFromBytes(raw.get("orderID").getAsString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
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
