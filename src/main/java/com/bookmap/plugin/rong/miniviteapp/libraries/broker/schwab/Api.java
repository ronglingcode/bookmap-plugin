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

/** Actual Schwab responses, not ProxyServer's synthetic JSON envelope. No retries or credential logging. */
public final class Api {
    private static final DateTimeFormatter ENTRY_LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private final HttpClient client;
    private final URI base;
    private final BiConsumer<String, String> log;
    public Api() { this(URI.create("https://api.schwabapi.com/trader/v1/")); }
    public Api(BiConsumer<String, String> log) { this(URI.create("https://api.schwabapi.com/trader/v1/"), log); }
    public Api(URI base) { this(base, (symbol, message) -> { }); }
    public Api(URI base, BiConsumer<String, String> log) {
        this.base = base;
        this.log = log;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public static final class Result {
        public final String method, orderId, newOrderId, outcome, reason;
        public final int status;
        public Result(Request request, HttpResponse<String> response, String token, String account) {
            method = request.method; orderId = request.orderId; status = response.statusCode();
            String location = response.headers().firstValue("location").orElse("");
            String id = location.substring(location.lastIndexOf('/') + 1);
            newOrderId = id.matches("[0-9]+") ? id : "";
            boolean success = method.equals("DELETE") ? status == 200 || status == 204
                    : status == 200 || status == 201 || status == 204;
            outcome = success && (!method.equals("POST") || !newOrderId.isEmpty()) ? "accepted"
                    : status >= 500 || success ? "unknown" : "rejected";
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
    private HttpResponse<String> send(String account, String token, String path, String method, JsonObject body)
            throws Exception {
        return send(account, token, path, method, body, null);
    }
    private HttpResponse<String> send(String account, String token, String path, String method, JsonObject body,
            String timedEntrySymbol) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve("accounts/" + account + path))
                .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString()));
        HttpRequest request = builder.build();
        long startedAt = System.nanoTime();
        if (timedEntrySymbol != null)
            log.accept(timedEntrySymbol, "Native entry POST sending to broker at " + LocalDateTime.now().format(ENTRY_LOG_TIME));
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
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
                    + response.statusCode() + ")");
        return response;
    }
    private static String httpFailure(String operation, HttpResponse<String> response, String token, String account) {
        String body = response.body();
        return ExecutionDiagnostics.sanitize(operation + " HTTP " + response.statusCode() + ": " +
                (body == null || body.isBlank() ? "empty broker response body" : body), token, account);
    }
    private JsonObject read(String account, String token, String path) throws Exception {
        HttpResponse<String> response = send(account, token, path, "GET", null);
        Models.require(response.statusCode() == 200, httpFailure("GET account positions", response, token, account));
        try { return JsonParser.parseString(response.body()).getAsJsonObject(); }
        catch (RuntimeException error) { throw new java.io.IOException("GET account positions HTTP 200 response parsing failed", error); }
    }
    public void validateFlatEntry(String account, String token, String symbol) throws Exception {
        String operation = "GET account positions";
        try {
            JsonObject data = read(account, token, "?fields=positions").getAsJsonObject("securitiesAccount");
            operation = "inspect GET account positions response (HTTP 200)";
            if (data.has("positions")) for (var element : data.getAsJsonArray("positions")) {
                JsonObject position = element.getAsJsonObject();
                if (symbol.equals(Models.string(position.getAsJsonObject("instrument"), "symbol"))) {
                    Models.require(Models.number(position, "longQuantity") == 0 && Models.number(position, "shortQuantity") == 0,
                            "initial entry requires a flat broker position: long=" + Models.number(position, "longQuantity")
                                    + ", short=" + Models.number(position, "shortQuantity"));
                }
            }
            var now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            operation = "GET pending symbol orders";
            HttpResponse<String> response = send(account, token, "/orders?maxResults=3000&fromEnteredTime=" +
                    now.minusSeconds(59L * 86400) + "&toEnteredTime=" + now, "GET", null);
            Models.require(response.statusCode() == 200, httpFailure(operation, response, token, account));
            operation = "parse GET pending symbol orders response (HTTP 200)";
            var list = JsonParser.parseString(response.body()).getAsJsonArray();
            operation = "inspect GET pending symbol orders response (HTTP 200)";
            Models.require(list.size() < 3000, "entry order read reached 3000 results and may be truncated");
            for (var element : list) requireNoSymbolOrders(element.getAsJsonObject(), symbol);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new java.io.IOException(operation + " failed", error);
        }
    }
    private void requireNoSymbolOrders(JsonObject order, String symbol) {
        String status = Models.string(order, "status");
        boolean working = !(status.equals("FILLED") || status.equals("CANCELED") || status.equals("REJECTED")
            || status.equals("EXPIRED") || status.equals("REPLACED"));
        if (working && order.has("orderLegCollection")) for (var leg : order.getAsJsonArray("orderLegCollection"))
            Models.require(!symbol.equals(Models.string(leg.getAsJsonObject().getAsJsonObject("instrument"), "symbol")),
                    "broker has pending symbol order: orderId=" + Models.string(order, "orderId") + ", status=" + status);
        if (order.has("childOrderStrategies")) for (var child : order.getAsJsonArray("childOrderStrategies")) requireNoSymbolOrders(child.getAsJsonObject(), symbol);
    }
    public Result mutate(String account, String token, Request request) throws Exception {
        return new Result(request, send(account, token, "/orders" +
                (request.orderId.isEmpty() ? "" : "/" + request.orderId), request.method, request.body), token, account);
    }
    public Result mutateEntry(String account, String token, Request request, String symbol) throws Exception {
        return new Result(request, send(account, token, "/orders", "POST", request.body, symbol), token, account);
    }
}
