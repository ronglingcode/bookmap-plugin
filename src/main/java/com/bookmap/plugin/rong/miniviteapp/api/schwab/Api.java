package com.bookmap.plugin.rong.miniviteapp.api.schwab;

import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.bookmap.plugin.rong.miniviteapp.models.Models.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Actual Schwab responses, not ProxyServer's synthetic JSON envelope. No retries or credential logging. */
public final class Api {
    private final HttpClient client;
    private final URI base;
    public Api() { this(URI.create("https://api.schwabapi.com/trader/v1/")); }
    public Api(URI base) {
        this.base = base;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public static final class Result {
        public final String method, orderId, newOrderId, outcome;
        public final int status;
        public Result(Request request, HttpResponse<String> response) {
            method = request.method; orderId = request.orderId; status = response.statusCode();
            String location = response.headers().firstValue("location").orElse("");
            String id = location.substring(location.lastIndexOf('/') + 1);
            newOrderId = id.matches("[0-9]+") ? id : "";
            boolean success = method.equals("DELETE") ? status == 200 || status == 204
                    : status == 200 || status == 201 || status == 204;
            outcome = success && (!method.equals("POST") || !newOrderId.isEmpty()) ? "accepted"
                    : status >= 500 || success ? "unknown" : "rejected";
        }
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("method", method); json.addProperty("orderId", orderId);
            json.addProperty("newOrderId", newOrderId); json.addProperty("status", status);
            json.addProperty("outcome", outcome); return json;
        }
    }
    private HttpResponse<String> send(String account, String token, String path, String method, JsonObject body)
            throws Exception {
        Models.require(account.matches("[A-Za-z0-9]+"), "invalid account hash");
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve("accounts/" + account + path))
                .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString()));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonObject read(String account, String token, String path) throws Exception {
        HttpResponse<String> response = send(account, token, path, "GET", null);
        Models.require(response.statusCode() == 200, "broker read failed; reconcile account");
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
    public double getPosition(String account, String token, String symbol) throws Exception {
        JsonObject wrapper = read(account, token, "?fields=positions");
        JsonObject data = wrapper.getAsJsonObject("securitiesAccount");
        double quantity = 0;
        if (data.has("positions")) for (var element : data.getAsJsonArray("positions")) {
            JsonObject position = element.getAsJsonObject();
            if (symbol.equals(Models.string(position.getAsJsonObject("instrument"), "symbol"))) {
                double longs = Models.number(position, "longQuantity"), shorts = Models.number(position, "shortQuantity");
                Models.require(Double.isFinite(longs) && Double.isFinite(shorts), "invalid broker position");
                quantity += longs - shorts;
            }
        }
        return quantity;
    }
    public void validateOrder(String account, String token, Order expected, boolean closing) throws Exception {
        JsonObject order = read(account, token, "/orders/" + expected.id);
        String status = Models.string(order, "status");
        Models.require(status.equals("WORKING") || status.equals("QUEUED") || status.equals("ACCEPTED")
                || status.equals("AWAITING_PARENT_ORDER"), "order is no longer working; reconcile first");
        var legs = order.getAsJsonArray("orderLegCollection");
        Models.require(legs != null && legs.size() == 1, "unsupported broker order legs");
        JsonObject leg = legs.get(0).getAsJsonObject();
        Models.require(expected.symbol.equals(Models.string(leg.getAsJsonObject("instrument"), "symbol"))
                && "EQUITY".equals(Models.string(leg.getAsJsonObject("instrument"), "assetType")), "broker instrument changed");
        String instruction = Models.string(leg, "instruction");
        Models.require(instruction.equals(closing ? (expected.isBuy ? "BUY_TO_COVER" : "SELL")
                : (expected.isBuy ? "BUY" : "SELL_SHORT")), "broker order side changed");
        Models.require(expected.type.equals(Models.string(order, "orderType")), "broker order type changed");
        // Any partial fill invalidates this snapshot. Do not submit its original full quantity again.
        double filled = Models.number(order, "filledQuantity");
        if (!Double.isFinite(filled)) filled = 0;
        Models.require(filled == 0 && Models.number(order, "quantity") == expected.quantity, "order quantity changed; reconcile first");
        if (!expected.type.equals("MARKET")) {
            double price = Models.number(order, expected.type.equals("STOP") ? "stopPrice" : "price");
            Models.require(Math.abs(price - expected.price) < 1e-8, "broker price changed; reconcile first");
        }
    }
    public void validateFlatEntry(String account, String token, String symbol, JsonObject entry, double estimatedEntryPrice) throws Exception {
        JsonObject data = read(account, token, "?fields=positions").getAsJsonObject("securitiesAccount");
        if (data.has("positions")) for (var element : data.getAsJsonArray("positions")) {
            JsonObject position = element.getAsJsonObject();
            if (symbol.equals(Models.string(position.getAsJsonObject("instrument"), "symbol"))) {
                Models.require(Models.number(position, "longQuantity") == 0 && Models.number(position, "shortQuantity") == 0,
                        "initial entry position changed");
            }
        }
        var balances = data.getAsJsonObject("currentBalances");
        double buyingPower = Models.number(balances, "buyingPower");
        double dayPower = Models.number(balances, "dayTradingBuyingPower");
        if (Double.isFinite(dayPower) && dayPower >= 0) buyingPower = Math.min(buyingPower, dayPower);
        double quantity = Models.number(entry.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject(), "quantity");
        double estimate = entry.has("price") ? Models.number(entry, "price") : entry.has("stopPrice") ? Models.number(entry, "stopPrice") : estimatedEntryPrice;
        Models.require(Models.positive(buyingPower) && buyingPower > quantity * estimate, "broker buying power blocks entry");
        var now = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        HttpResponse<String> response = send(account, token, "/orders?maxResults=3000&fromEnteredTime=" +
                now.minusSeconds(59L * 86400) + "&toEnteredTime=" + now, "GET", null);
        Models.require(response.statusCode() == 200, "entry orders preflight failed");
        var list = JsonParser.parseString(response.body()).getAsJsonArray();
        Models.require(list.size() < 3000, "entry order read may be truncated; reconcile first");
        for (var element : list) requireNoSymbolOrders(element.getAsJsonObject(), symbol);
    }
    private void requireNoSymbolOrders(JsonObject order, String symbol) {
        String status = Models.string(order, "status");
        boolean working = !(status.equals("FILLED") || status.equals("CANCELED") || status.equals("REJECTED")
            || status.equals("EXPIRED") || status.equals("REPLACED"));
        if (working && order.has("orderLegCollection")) for (var leg : order.getAsJsonArray("orderLegCollection"))
            Models.require(!symbol.equals(Models.string(leg.getAsJsonObject().getAsJsonObject("instrument"), "symbol")), "broker has pending symbol orders");
        if (order.has("childOrderStrategies")) for (var child : order.getAsJsonArray("childOrderStrategies")) requireNoSymbolOrders(child.getAsJsonObject(), symbol);
    }
    public Result mutate(String account, String token, Request request) throws Exception {
        return new Result(request, send(account, token, "/orders" +
                (request.orderId.isEmpty() ? "" : "/" + request.orderId), request.method, request.body));
    }
}
