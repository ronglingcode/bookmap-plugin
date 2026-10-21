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
    private static final DateTimeFormatter ORDER_TIME = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME).appendPattern("[XXX][XX]").toFormatter();
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
            window(account, token, start, end, orders);
        }
        else {
            long today = Instant.parse(date + "T00:00:00Z").toEpochMilli();
            // Only pre-split today; older history subdivides on demand.
            if (start < today) window(account, token, start, today, orders);
            for (long from = today; from < end; from += 3600000L) {
                long to = from + 3600000L;
                if (MarketClock.marketTime(from + 1800000L).minutesSinceMarketOpen != 0) window(account, token, from, to, orders);
                else {
                    window(account, token, from, from + 1800000, orders);
                    for (long cursor = from + 1800000; cursor < to; cursor += 300000) window(account, token, cursor, cursor + 300000, orders);
                }
            }
        }
        JsonArray result = new JsonArray(); orders.values().forEach(result::add); return result;
    }
    private void window(String account, String token, long from, long to, Map<String, JsonObject> orders) throws Exception {
        JsonArray array = readOrderWindow(account, token, from, to);
        // Brackets may be truncated below 500 roots. Count nested records
        // conservatively, including child orders, legs and execution activities.
        if (recordCount(array, from, to, true) >= 500) {
            // Inclusive endpoints avoid gaps; collectOrders removes boundary duplicates.
            if (to - from <= 1) throw new IOException("Schwab orders reached 500 in a one-millisecond window; read may be incomplete");
            long middle = from + (to - from) / 2;
            window(account, token, from, middle, orders);
            window(account, token, middle, to, orders);
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
    private static int recordCount(JsonElement value, long from, long to, boolean inWindow) {
        int count = 0;
        if (value.isJsonArray()) for (JsonElement item : value.getAsJsonArray()) count += recordCount(item, from, to, inWindow);
        else if (value.isJsonObject()) {
            JsonElement enteredTime = value.getAsJsonObject().get("enteredTime");
            if (enteredTime != null && enteredTime.isJsonPrimitive()) {
                try {
                    long entered = java.time.OffsetDateTime.parse(enteredTime.getAsString(), ORDER_TIME).toInstant().toEpochMilli();
                    inWindow = entered >= from && entered <= to;
                } catch (java.time.format.DateTimeParseException ignored) { }
            }
            // Older working trees are returned even outside the requested window.
            count = inWindow ? 1 : 0;
            for (JsonElement item : value.getAsJsonObject().asMap().values()) count += recordCount(item, from, to, inWindow);
        }
        return count;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
}
