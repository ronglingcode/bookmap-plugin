package com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab;

import com.bookmap.plugin.rong.miniviteapp.ports.CredentialPort;
import com.bookmap.plugin.rong.miniviteapp.ports.HttpPort;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Only refreshes share this monitor; no manual action execution lock. */
public final class OAuth {
    private final HttpPort http;
    private final CredentialPort credentials;
    private final URI endpoint;
    private final LongSupplier now;
    public OAuth(HttpPort http, CredentialPort credentials) {
        this(http, credentials, URI.create("https://api.schwabapi.com/v1/oauth/token"), System::currentTimeMillis);
    }
    public OAuth(HttpPort http, CredentialPort credentials, URI endpoint, LongSupplier now) {
        this.http = http; this.credentials = credentials; this.endpoint = endpoint; this.now = now;
    }
    public String accessToken(boolean force) throws Exception {
        JsonObject value = credentials.loadSchwab();
        if (!force && !string(value, "access_token").isEmpty() && number(value, "expires_at") > now.getAsLong() + 60000)
            return string(value, "access_token");
        return string(refresh(), "access_token");
    }
    public JsonObject refresh() throws Exception {
        JsonObject before = credentials.loadSchwab();
        synchronized (this) {
            JsonObject value = credentials.loadSchwab();
            if (!before.equals(value)) return value; // another in-flight refresh already persisted its result
            Map<String, String> data = new LinkedHashMap<>(); data.put("grant_type", "refresh_token"); data.put("refresh_token", string(value, "refresh_token"));
            return exchange(data, value);
        }
    }
    public synchronized JsonObject exchangeAuthorizationCode(String callbackUrl) throws Exception {
        String code = "", query = URI.create(callbackUrl).getRawQuery();
        if (query != null) for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair[0].equals("code") && pair.length > 1) code = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
        }
        if (code.isEmpty()) throw new IOException("Schwab callback has no authorization code");
        JsonObject value = credentials.loadSchwab();
        Map<String, String> data = new LinkedHashMap<>(); data.put("grant_type", "authorization_code"); data.put("code", code);
        String redirect = string(value, "redirectUrl"); data.put("redirect_uri", redirect.isEmpty() ? "https://127.0.0.1" : redirect);
        return exchange(data, value);
    }
    private JsonObject exchange(Map<String, String> data, JsonObject previous) throws Exception {
        if (string(previous, "appKey").isEmpty() || string(previous, "secret").isEmpty()) throw new IOException("Schwab app credentials are missing");
        if (data.get("grant_type").equals("refresh_token") && data.get("refresh_token").isEmpty())
            throw new IOException("Schwab refresh token is missing; authorize again");
        Map<String, String> headers = new LinkedHashMap<>(); headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString((string(previous, "appKey") + ":" + string(previous, "secret")).getBytes(StandardCharsets.UTF_8)));
        StringBuilder body = new StringBuilder();
        data.forEach((key, value) -> { if (body.length() != 0) body.append('&'); body.append(encode(key)).append('=').append(encode(value)); });
        HttpPort.Response response = http.request(endpoint, "POST", headers, body.toString());
        if (response.status != 200) throw new IOException("Schwab OAuth HTTP " + response.status
                + (response.status == 400 || response.status == 401 ? "; authorize again if refresh token is revoked" : ""));
        JsonObject result;
        try { result = JsonParser.parseString(response.body).getAsJsonObject(); }
        catch (RuntimeException error) { throw new IOException("Schwab OAuth returned invalid JSON"); }
        double expiry = number(result, "expires_in");
        if (string(result, "access_token").isEmpty() || !Double.isFinite(expiry) || expiry <= 0)
            throw new IOException("Schwab OAuth returned no usable token/expiry");
        JsonObject updated = previous.deepCopy(); updated.addProperty("access_token", string(result, "access_token"));
        if (!string(result, "refresh_token").isEmpty()) updated.addProperty("refresh_token", string(result, "refresh_token"));
        updated.addProperty("expires_at", now.getAsLong() + expiry * 1000);
        credentials.saveSchwab(updated); return updated;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String string(JsonObject json, String name) {
        return json.has(name) && json.get(name).isJsonPrimitive() && json.getAsJsonPrimitive(name).isString() ? json.get(name).getAsString() : "";
    }
    private static double number(JsonObject json, String name) {
        return json.has(name) && json.get(name).isJsonPrimitive() && json.getAsJsonPrimitive(name).isNumber() ? json.get(name).getAsDouble() : 0;
    }
}
