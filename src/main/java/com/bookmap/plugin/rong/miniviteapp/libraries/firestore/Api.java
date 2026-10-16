package com.bookmap.plugin.rong.miniviteapp.libraries.firestore;

import com.bookmap.plugin.rong.miniviteapp.ports.HttpPort;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalTime;
import java.time.format.DateTimeFormatterBuilder;

/** Uses existing Firestore security rules, not an admin service account. */
public final class Api {
    private final HttpPort http;
    private final String root;
    private final Supplier<String> apiKey, idToken;
    private final LongSupplier now;
    public Api(HttpPort http, String projectId, Supplier<String> apiKey) { this(http, projectId, apiKey, () -> ""); }
    public Api(HttpPort http, String projectId, Supplier<String> apiKey, Supplier<String> idToken) {
        this(http, projectId, apiKey, idToken, System::currentTimeMillis);
    }
    public Api(HttpPort http, String projectId, Supplier<String> apiKey, Supplier<String> idToken, LongSupplier now) {
        this.http = http; this.root = "https://firestore.googleapis.com/v1/projects/" + encode(projectId) + "/databases/(default)/documents";
        this.apiKey = apiKey; this.idToken = idToken;
        this.now = now;
    }
    private JsonElement request(String path, String method, JsonObject body, boolean missingAllowed) throws Exception {
        String url = root + path; String key = apiKey.get(); if (!key.isEmpty()) url += "?key=" + encode(key);
        Map<String, String> headers = new LinkedHashMap<>(); headers.put("Content-Type", "application/json");
        String token = idToken.get(); if (!token.isEmpty()) headers.put("Authorization", "Bearer " + token);
        HttpPort.Response response = http.request(URI.create(url), method, headers, body == null ? null : body.toString());
        if (missingAllowed && response.status == 404) return null;
        if (response.status < 200 || response.status >= 300) throw new IOException("Firestore " + method + " HTTP " + response.status);
        try { return JsonParser.parseString(response.body); } catch (RuntimeException error) { throw new IOException("Firestore returned invalid JSON"); }
    }
    public JsonObject getDocument(String path) throws Exception {
        JsonElement json = request("/" + encodePath(path), "GET", null, true);
        return json == null ? null : DocumentCodec.decodeFields(json.getAsJsonObject().getAsJsonObject("fields"));
    }
    public void setDocument(String path, JsonObject data) throws Exception {
        JsonObject body = new JsonObject(); body.add("fields", DocumentCodec.encodeFields(data));
        request("/" + encodePath(path), "PATCH", body, false);
    }
    public JsonObject fetchConfigData() throws Exception {
        JsonObject query = JsonParser.parseString("{\"structuredQuery\":{\"from\":[{\"collectionId\":\"configDataSnapshot\"}],\"orderBy\":[{\"field\":{\"fieldPath\":\"timestamp\"},\"direction\":\"DESCENDING\"}],\"limit\":1}}").getAsJsonObject();
        // Exclude first-hour edits before limit: 1 so they cannot hide an eligible plan.
        ZoneId zone = ZoneId.of("America/New_York");
        Instant open = Instant.ofEpochMilli(now.getAsLong()).atZone(zone).toLocalDate().atTime(LocalTime.of(9, 30)).atZone(zone).toInstant();
        JsonObject filter = JsonParser.parseString("{\"fieldFilter\":{\"field\":{\"fieldPath\":\"timestamp\"},\"op\":\"LESS_THAN\",\"value\":{}}}").getAsJsonObject();
        filter.getAsJsonObject("fieldFilter").getAsJsonObject("value").addProperty("timestampValue", new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(open));
        JsonObject afterFirstHour = filter.deepCopy();
        afterFirstHour.getAsJsonObject("fieldFilter").addProperty("op", "GREATER_THAN_OR_EQUAL");
        afterFirstHour.getAsJsonObject("fieldFilter").getAsJsonObject("value").addProperty("timestampValue", new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(open.plusSeconds(3600)));
        JsonArray filters = new JsonArray(); filters.add(filter); filters.add(afterFirstHour);
        JsonObject composite = new JsonObject(); composite.addProperty("op", "OR"); composite.add("filters", filters);
        JsonObject where = new JsonObject(); where.add("compositeFilter", composite);
        query.getAsJsonObject("structuredQuery").add("where", where);
        JsonElement rows = request(":runQuery", "POST", query, false);
        if (!rows.isJsonArray()) throw new IOException("Firestore config query returned invalid rows");
        for (JsonElement row : rows.getAsJsonArray()) if (row.isJsonObject() && row.getAsJsonObject().has("document"))
            return DocumentCodec.decodeFields(row.getAsJsonObject().getAsJsonObject("document").getAsJsonObject("fields"));
        throw new IOException("Firestore has no configuration snapshot");
    }
    public JsonObject getTradingState(String profile) throws Exception { return getDocument("state-" + profile + "/tradingState"); }
    public void setTradingState(String profile, JsonObject state) throws Exception { setDocument("state-" + profile + "/tradingState", state); }
    public void addDocument(String collection, JsonObject data) throws Exception {
        JsonObject body = new JsonObject(); body.add("fields", DocumentCodec.encodeFields(data));
        request("/" + encodePath(collection), "POST", body, false);
    }
    private static String encodePath(String value) {
        return java.util.Arrays.stream(value.split("/", -1)).map(Api::encode).collect(java.util.stream.Collectors.joining("/"));
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
}
