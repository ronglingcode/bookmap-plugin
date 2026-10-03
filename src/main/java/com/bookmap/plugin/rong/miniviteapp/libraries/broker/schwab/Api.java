package com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab;

import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.bookmap.plugin.rong.miniviteapp.ExecutionDiagnostics;
import com.bookmap.plugin.rong.miniviteapp.models.Models.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.BiConsumer;
import com.bookmap.plugin.rong.miniviteapp.ports.HttpPort;
import java.util.LinkedHashMap;
import java.util.Map;

/** Actual Schwab responses, not ProxyServer's synthetic JSON envelope. No retries or credential logging. */
public final class Api {
    private static final DateTimeFormatter ENTRY_LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private final HttpPort http;
    private final URI base;
    private final BiConsumer<String, String> log;
    public Api() { this(URI.create("https://api.schwabapi.com/trader/v1/")); }
    public Api(BiConsumer<String, String> log) { this(URI.create("https://api.schwabapi.com/trader/v1/"), log); }
    public Api(URI base) { this(base, (symbol, message) -> { }); }
    public Api(URI base, BiConsumer<String, String> log) { this(base, nativeHttp(), log); }
    public Api(HttpPort http, BiConsumer<String, String> log) { this(URI.create("https://api.schwabapi.com/trader/v1/"), http, log); }
    public Api(URI base, HttpPort http, BiConsumer<String, String> log) { this.base = base; this.http = http; this.log = log; }
    private static HttpPort nativeHttp() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        return (uri, method, headers, body) -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)); headers.forEach(request::header);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, String> returned = new LinkedHashMap<>(); response.headers().map().forEach((key, values) -> returned.put(key, String.join(",", values)));
            return new HttpPort.Response(response.statusCode(), response.body(), returned);
        };
    }
    public static final class Result {
        public final String method, orderId, newOrderId, outcome, reason;
        public final int status;
        public Result(Request request, HttpPort.Response response, String token, String account) {
            method = request.method; orderId = request.orderId; status = response.status;
            String location = response.header("location");
            String id = location.substring(location.lastIndexOf('/') + 1);
            newOrderId = id.matches("[0-9]+") ? id : "";
            boolean success = method.equals("DELETE") ? status == 200 || status == 204
                    : status == 200 || status == 201 || status == 204;
            outcome = success && (!method.equals("POST") || !newOrderId.isEmpty()) ? "accepted"
                    : status >= 500 || status == 408 || status >= 200 && status < 400 ? "unknown" : "rejected";
            String operation = method + (orderId.isEmpty() ? " new order" : " order " + orderId);
            reason = ExecutionDiagnostics.sanitize(outcome.equals("accepted") ? "" : success
                    ? operation + " HTTP " + status + ": " + (location.isEmpty() ? "missing Location header" : "Location header has no numeric order ID: " + location)
                    : httpFailure(operation, response, token, account), token, account);
        }
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("method", method); json.addProperty("orderId", orderId);
            json.addProperty("newOrderId", newOrderId); json.addProperty("status", status);
            json.addProperty("outcome", outcome); json.addProperty("reason", reason); return json;
        }
    }
    private HttpPort.Response send(String account, String token, String path, String method, JsonObject body)
            throws Exception {
        return send(account, token, path, method, body, null);
    }
    private HttpPort.Response send(String account, String token, String path, String method, JsonObject body,
            String timedEntrySymbol) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>(); headers.put("Authorization", "Bearer " + token); headers.put("Accept", "application/json");
        if (body != null) headers.put("Content-Type", "application/json");
        long startedAt = System.nanoTime();
        if (timedEntrySymbol != null)
            log.accept(timedEntrySymbol, "Native entry POST attempting HTTP request at " + LocalDateTime.now().format(ENTRY_LOG_TIME));
        HttpPort.Response response;
        try {
            response = http.request(base.resolve("accounts/" + account + path), method, headers, body == null ? null : body.toString());
        } catch (Exception error) {
            if (timedEntrySymbol != null)
                log.accept(timedEntrySymbol, "Native entry POST received no response after "
                        + Duration.ofNanos(System.nanoTime() - startedAt).toMillis() + " ms");
            throw error;
        }
        if (timedEntrySymbol != null)
            log.accept(timedEntrySymbol, "Native entry POST response received at "
                    + LocalDateTime.now().format(ENTRY_LOG_TIME) + " after "
                    + Duration.ofNanos(System.nanoTime() - startedAt).toMillis() + " ms (HTTP "
                    + response.status + ")");
        return response;
    }
    private static String httpFailure(String operation, HttpPort.Response response, String token, String account) {
        String body = response.body;
        return ExecutionDiagnostics.sanitize(operation + " HTTP " + response.status + ": " +
                (body == null || body.isBlank() ? "empty broker response body" : body), token, account);
    }
    public Result mutate(String account, String token, Request request) throws Exception {
        return new Result(request, send(account, token, "/orders" +
                (request.orderId.isEmpty() ? "" : "/" + request.orderId), request.method, request.body), token, account);
    }
    public Result mutateEntry(String account, String token, Request request, String symbol) throws Exception {
        return new Result(request, send(account, token, "/orders", "POST", request.body, symbol), token, account);
    }
}
