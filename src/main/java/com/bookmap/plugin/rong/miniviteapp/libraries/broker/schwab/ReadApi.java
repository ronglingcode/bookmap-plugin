package com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab;

import com.bookmap.plugin.rong.miniviteapp.core.marketdata.MarketClock;
import com.bookmap.plugin.rong.miniviteapp.ports.HttpPort;
import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.Map;

/** Mirrors TS SchwabReadApi: no mutations, no proxy dependency. */
public final class ReadApi {
    private static final DateTimeFormatter ISO = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final HttpPort http;
    private final String base;
    public ReadApi(HttpPort http) { this(http, "https://api.schwabapi.com/trader/v1"); }
    public ReadApi(HttpPort http, String base) { this.http = http; this.base = base.replaceAll("/$", ""); }
    private JsonElement read(String path, String token) throws Exception {
        HttpPort.Response response = http.request(URI.create(base + "/" + path), "GET", Map.of("Authorization", "Bearer " + token, "Accept", "application/json"), null);
        if (response.status != 200) throw new IOException("Schwab read HTTP " + response.status);
        try { return JsonParser.parseString(response.body); } catch (RuntimeException error) { throw new IOException("Schwab read response is not JSON"); }
    }
    public JsonObject getAccount(String token) throws Exception {
        JsonElement value = read("accounts?fields=positions", token);
        if (!value.isJsonArray() || value.getAsJsonArray().size() == 0 || !value.getAsJsonArray().get(0).isJsonObject()
                || !value.getAsJsonArray().get(0).getAsJsonObject().has("securitiesAccount")) throw new IOException("Schwab account response missing securitiesAccount");
        return value.getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("securitiesAccount");
    }
    public JsonObject getStreamerInfo(String token) throws Exception {
        JsonElement value = read("userPreference", token); JsonObject info = null;
        if (value.isJsonObject()) {
            JsonElement array = value.getAsJsonObject().get("streamerInfo");
            if (array != null && array.isJsonArray() && array.getAsJsonArray().size() > 0 && array.getAsJsonArray().get(0).isJsonObject()) info = array.getAsJsonArray().get(0).getAsJsonObject();
        }
        JsonObject result = new JsonObject();
        for (String field : new String[]{"streamerSocketUrl", "schwabClientCustomerId", "schwabClientCorrelId", "schwabClientChannel", "schwabClientFunctionId"}) {
            if (info == null || !info.has(field) || !info.get(field).isJsonPrimitive() || !info.get(field).getAsJsonPrimitive().isString() || info.get(field).getAsString().isEmpty()) throw new IOException("Schwab preferences missing streamer information");
            result.add(field, info.get(field));
        }
        return result;
    }
    public JsonArray getOrders(String account, String token, String date, boolean useTimeWindows) throws Exception {
        return getOrders(account, token, date, useTimeWindows, 0);
    }
    /** Include older working orders that can receive fills during today's session. */
    public JsonArray getOrders(String account, String token, String date, boolean useTimeWindows, int lookbackDays) throws Exception {
        if (lookbackDays < 0 || lookbackDays > 59) throw new IllegalArgumentException("Order lookback must be 0-59 days");
        long start = Instant.parse(LocalDate.parse(date).minusDays(lookbackDays) + "T00:00:00Z").toEpochMilli(), end = Instant.parse(LocalDate.parse(date).plusDays(1) + "T00:00:00Z").toEpochMilli();
        Map<String, JsonObject> orders = new LinkedHashMap<>();
        if (!useTimeWindows) {
            if (lookbackDays == 0) window(account, token, start, end, 3600000, orders);
            else wideWindow(account, token, start, end, orders);
        }
        else {
            for (long from = start; from < end; from += 3600000L) {
                long to = from + 3600000L;
                if (MarketClock.marketTime(from + 1800000L).minutesSinceMarketOpen != 0) window(account, token, from, to, 600000, orders);
                else {
                    window(account, token, from, from + 1800000, 60000, orders);
                    for (long cursor = from + 1800000; cursor < to; cursor += 300000) window(account, token, cursor, cursor + 300000, 60000, orders);
                }
            }
        }
        JsonArray result = new JsonArray(); orders.values().forEach(result::add); return result;
    }
    private void window(String account, String token, long from, long to, long subdivision, Map<String, JsonObject> orders) throws Exception {
        JsonArray array = readOrderWindow(account, token, from, to);
        if (array.size() >= 500) {
            if (to - from <= 60000) throw new IOException("Schwab orders reached 500 in a one-minute window; read may be incomplete");
            for (long cursor = from; cursor < to; cursor += subdivision) window(account, token, cursor, Math.min(to, cursor + subdivision), subdivision >= 3600000 ? 600000 : 60000, orders);
            return;
        }
        collectOrders(array, orders);
    }
    private void wideWindow(String account, String token, long from, long to, Map<String, JsonObject> orders) throws Exception {
        JsonArray array = readOrderWindow(account, token, from, to);
        if (array.size() >= 500) {
            if (to - from <= 60000) throw new IOException("Schwab orders reached 500 in a one-minute window; read may be incomplete");
            long middle = from + (to - from) / 2;
            wideWindow(account, token, from, middle, orders);
            wideWindow(account, token, middle, to, orders);
            return;
        }
        collectOrders(array, orders);
    }
    private JsonArray readOrderWindow(String account, String token, long from, long to) throws Exception {
        String query = "fromEnteredTime=" + encode(ISO.format(Instant.ofEpochMilli(from))) + "&toEnteredTime=" + encode(ISO.format(Instant.ofEpochMilli(to))) + "&maxResults=500";
        JsonElement data = read("accounts/" + encode(account) + "/orders?" + query, token);
        JsonElement list = data.isJsonArray() ? data : data.isJsonObject() ? data.getAsJsonObject().get("orders") : null;
        if (list == null || !list.isJsonArray()) throw new IOException("Schwab order response is not an array");
        return list.getAsJsonArray();
    }
    private void collectOrders(JsonArray array, Map<String, JsonObject> orders) throws IOException {
        for (JsonElement element : array) {
            JsonObject order = element.getAsJsonObject(); JsonElement id = order.has("orderId") ? order.get("orderId") : order.get("orderID");
            if (id == null || id.isJsonNull()) throw new IOException("Schwab order missing orderId");
            orders.put(id.getAsString(), order);
        }
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
}
