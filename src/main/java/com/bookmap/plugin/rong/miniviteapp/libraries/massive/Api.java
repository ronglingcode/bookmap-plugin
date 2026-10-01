package com.bookmap.plugin.rong.miniviteapp.libraries.massive;

import com.bookmap.plugin.rong.miniviteapp.core.marketdata.PremarketVolume;
import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import com.bookmap.plugin.rong.miniviteapp.ports.HttpPort;
import com.bookmap.plugin.rong.miniviteapp.core.marketdata.MarketClock;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/** Same contract as src/trading/libraries/massive/api.ts. Never logs credential URLs. */
public final class Api {
    private final HttpPort http;
    private final Supplier<String> apiKey;
    private final URI base;
    public Api(HttpPort http, Supplier<String> apiKey) { this(http, apiKey, URI.create("https://api.massive.com")); }
    public Api(HttpPort http, Supplier<String> apiKey, URI base) { this.http = http; this.apiKey = apiKey; this.base = base; }

    private URI authenticatedUri(String path) {
        URI uri = base.resolve(path);
        if (!base.getScheme().equals(uri.getScheme()) || !base.getAuthority().equals(uri.getAuthority()))
            throw new IllegalArgumentException("Massive pagination returned another host");
        String value = uri.toString().replaceAll("([?&])apiKey=[^&]*&?", "$1").replaceAll("[?&]$", "");
        return URI.create(value + (value.contains("?") ? "&" : "?") + "apiKey=" + encode(apiKey.get()));
    }
    private JsonObject read(String path) throws Exception {
        HttpPort.Response response = http.request(authenticatedUri(path), "GET", Collections.emptyMap(), null);
        if (response.status != 200) throw new IOException("Massive read HTTP " + response.status);
        JsonObject json;
        try {
            JsonElement value = JsonParser.parseString(response.body);
            if (!value.isJsonObject()) throw new IllegalArgumentException();
            json = value.getAsJsonObject();
        } catch (RuntimeException error) { throw new IOException("Massive read returned invalid JSON object"); }
        String status = string(json, "status");
        if (status.equals("ERROR") || status.equals("NOT_AUTHORIZED")) throw new IOException("Massive read failed: " + status);
        return json;
    }
    public List<Candle> getBars(String symbol, String path) throws Exception {
        TreeMap<Long, Candle> bars = new TreeMap<>(); Set<String> seen = new HashSet<>();
        String next = path;
        while (!next.isEmpty()) {
            String page = authenticatedUri(next).toString();
            if (!seen.add(page)) throw new IOException("Massive repeated pagination cursor");
            JsonObject json = read(page);
            if (json.has("results")) {
                if (!json.get("results").isJsonArray()) throw new IOException("Massive read returned invalid results");
                for (JsonElement value : json.getAsJsonArray("results")) {
                    Candle candle = Mapper.mapAggregate(symbol, value.getAsJsonObject()); bars.put(candle.datetime, candle);
                }
            }
            next = string(json, "next_url");
        }
        return new ArrayList<>(bars.values());
    }
    private String aggregatePath(String symbol, int timeframe, String timespan, String start, String end, int limit) {
        if (timeframe <= 0) throw new IllegalArgumentException("Invalid Massive timeframe");
        return "/v2/aggs/ticker/" + encode(symbol) + "/range/" + timeframe + "/" + timespan + "/" + start + "/" + end
                + "?adjusted=true&sort=asc&limit=" + limit;
    }
    public List<Candle> getPriceHistory(String symbol, int timeframe, String today) throws Exception {
        return getBars(symbol, aggregatePath(symbol, timeframe, "minute", today, MarketClock.addDays(today, 1), 50000));
    }
    public List<Candle> getPriceHistoryFromOldDateForHigherTimeframe(String symbol, int timeframe, String start, String end) throws Exception {
        return getBars(symbol, aggregatePath(symbol, timeframe, "minute", start, end, 50000));
    }
    public List<Candle> getDailyCandlesForLastNDays(String symbol, int nDays, String endDateExcluded) throws Exception {
        String end = MarketClock.addDays(endDateExcluded, -1);
        return getBars(symbol, aggregatePath(symbol, 1, "day", MarketClock.addDays(end, -nDays - 1), end, 50000));
    }
    public double getSharesOutstanding(String symbol) throws Exception {
        JsonObject json = read("/v3/reference/tickers/" + encode(symbol));
        if (!json.has("results") || !json.get("results").isJsonObject()) return 0;
        JsonObject result = json.getAsJsonObject("results");
        double weighted = numberOrZero(result, "weighted_shares_outstanding");
        return weighted != 0 ? weighted : numberOrZero(result, "share_class_shares_outstanding");
    }
    public JsonObject getFullPriceHistory(String symbol, String today) throws Exception {
        List<Candle> todayBars = getPriceHistory(symbol, 1, today);
        List<Candle> dailyBars = getDailyCandlesForLastNDays(symbol, 3 * 365, today);
        List<Candle> premarket = getPriceHistoryFromOldDateForHigherTimeframe(symbol, 30, MarketClock.addDays(today, -20), today);
        JsonObject result = new JsonObject(); result.add("today1MinuteBars", toJson(todayBars)); result.add("dailyBars", toJson(dailyBars));
        result.add("premarketDollarCollection", PremarketVolume.calculatePremarketVolume(premarket)); return result;
    }
    private static JsonArray toJson(List<Candle> candles) {
        JsonArray result = new JsonArray(); candles.forEach(candle -> result.add(candle.toJson())); return result;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String string(JsonObject json, String name) {
        return json.has(name) && json.get(name).isJsonPrimitive() && json.getAsJsonPrimitive(name).isString() ? json.get(name).getAsString() : "";
    }
    private static double numberOrZero(JsonObject json, String name) {
        if (!json.has(name) || !json.get(name).isJsonPrimitive() || !json.getAsJsonPrimitive(name).isNumber()) return 0;
        double value = json.get(name).getAsDouble(); return Double.isFinite(value) ? value : 0;
    }
}
