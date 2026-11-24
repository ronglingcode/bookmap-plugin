package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.runtime.TradingRuntime;
import com.bookmap.plugin.rong.miniviteapp.ports.*;
import com.bookmap.plugin.rong.tradebuttons.PremarketTradingPanel;
import com.google.gson.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PremarketRuntimeTest {
    static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    static class Harness implements AutoCloseable {
        final AtomicLong now = new AtomicLong(Instant.parse("2026-11-23T14:00:00Z").toEpochMilli());
        final List<JsonObject> posted = new CopyOnWriteArrayList<>(); final List<String> logs = new CopyOnWriteArrayList<>();
        boolean enabled, storeOrders; final AtomicInteger targetedReads = new AtomicInteger();
        final Map<String, JsonObject> brokerOrders = new LinkedHashMap<>();
        final List<String> replaced = new ArrayList<>();
        final JsonObject secrets = json("{\"access_token\":\"fake-token\",\"accountHashValue\":\"selected-account\",\"expires_at\":9999999999999}");
        final String position = "{\"instrument\":{\"symbol\":\"AMD\",\"assetType\":\"EQUITY\"},\"longQuantity\":10,\"averagePrice\":100}";
        final String account = "{\"positions\":[" + position + "],\"currentBalances\":{\"liquidationValue\":25000}}";
        final HttpPort http = (uri, method, headers, body) -> {
            String path = uri.getPath();
            if (path.endsWith("/accounts")) return new HttpPort.Response(200, "[{\"securitiesAccount\":" + account + "}]");
            if (path.endsWith("/accounts/selected-account")) { targetedReads.incrementAndGet(); return new HttpPort.Response(200, "{\"securitiesAccount\":" + account + "}"); }
            if (path.endsWith("/orders")) {
                if (method.equals("POST")) {
                    posted.add(json(body)); String id = "" + (122 + posted.size());
                    if (storeOrders) remember(id, json(body));
                    return new HttpPort.Response(201, "", Map.of("Location", "https://fake.invalid/orders/" + id));
                }
                JsonArray orders = new JsonArray(); brokerOrders.values().forEach(order -> orders.add(order.deepCopy()));
                return new HttpPort.Response(200, orders.toString());
            }
            if (path.contains("/orders/")) {
                String id = path.substring(path.lastIndexOf('/') + 1);
                if (method.equals("DELETE")) {
                    replaced.add(id); brokerOrders.get(id).addProperty("status", "CANCELED"); return new HttpPort.Response(204, "");
                }
                if (method.equals("PUT")) {
                    replaced.add(id); brokerOrders.get(id).addProperty("status", "REPLACED");
                    remember("124", json(body)); return new HttpPort.Response(201, "", Map.of("Location", "https://fake.invalid/orders/124"));
                }
                return new HttpPort.Response(200, brokerOrders.get(id).toString());
            }
            // No config, no saved state, no history or market-data vendor is required for manual trading.
            return new HttpPort.Response(503, "{}");
        };
        void remember(String id, JsonObject payload) {
            JsonObject raw = payload.deepCopy(); raw.addProperty("orderId", id); raw.addProperty("status", "WORKING");
            raw.addProperty("quantity", payload.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("quantity").getAsDouble());
            brokerOrders.put(id, raw);
        }
        final TradingRuntime runtime = new TradingRuntime(http, new CredentialPort() {
            public JsonObject loadSchwab() { return secrets.deepCopy(); }
            public void saveSchwab(JsonObject value) { fail("Token already valid"); }
        }, new JsonObject(), (uri, handlers) -> new SocketPort.Connection() { public void send(String value) {} public void close() {} },
                (delay, task) -> () -> {}, Runnable::run, now::get, new TradingRuntime.Events() {
                    public void message(JsonObject value) {}
                    public void log(String symbol, String value) { logs.add(value); }
                    public void notify(String symbol, String value) { logs.add(value); }
                });
        Harness() { runtime.setPremarketTrading(() -> enabled, symbol -> json("{\"bid\":100,\"ask\":101}")); }
        public void close() { runtime.close(); }
    }
    @Test void manualTradingWorksWithoutConfigHistoryOrPlanAndHonorsToggleAndBrokerPosition() {
        try (Harness h = new Harness()) {
            h.runtime.attachSymbol("AMD"); h.runtime.start().join();
            assertTrue(h.runtime.view("AMD", "test").has("account")); assertFalse(h.runtime.view("AMD", "test").has("plan"));
            JsonObject action = PremarketTradingPanel.action("AMD", "sell_now", "3", "1");
            h.runtime.dispatch(action); assertTrue(h.posted.isEmpty());
            h.enabled = true; h.runtime.dispatch(action);
            assertEquals(1, h.posted.size(), h.logs.toString()); assertEquals(1, h.targetedReads.get());
            JsonObject order = h.posted.get(0); assertEquals("SEAMLESS", order.get("session").getAsString());
            assertEquals("SELL", order.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("instruction").getAsString());
            assertEquals(99, order.get("price").getAsDouble());
            h.enabled = false; h.runtime.dispatch(action); assertEquals(1, h.posted.size());
        }
    }
    @Test void automaticProtectionDoesNotObserveOrClosePremarketAndStartsFreshAtOpen() throws Exception {
        try (Harness h = new Harness()) {
            h.runtime.start().join();
            for (int i = 0; i < 5; i++) { h.now.addAndGet(11000); h.runtime.refreshAccount(); }
            assertTrue(h.posted.isEmpty()); assertTrue(h.logs.stream().noneMatch(log -> log.contains("without stop loss")));
            h.now.set(Instant.parse("2026-11-23T14:30:00Z").toEpochMilli()); h.runtime.refreshAccount();
            assertTrue(h.posted.isEmpty()); assertTrue(h.logs.stream().anyMatch(log -> log.contains("without stop loss")));
            h.now.addAndGet(11001); h.runtime.refreshAccount();
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (h.posted.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
            assertFalse(h.posted.isEmpty(), h.logs.toString());
            assertEquals("MARKET", h.posted.get(0).get("orderType").getAsString(), "Existing regular-session automatic protection still operates");
        }
    }
    @Test void chartDigitCancelsThenResubmitsExactPremarketOrderWithoutPlanOrSelectedStop() {
        try (Harness h = new Harness()) {
            h.storeOrders = true; h.enabled = true; h.runtime.attachSymbol("AMD"); h.runtime.start().join();
            h.runtime.dispatch(PremarketTradingPanel.action("AMD", "buy_bid", "3", "1"));
            assertEquals(1, h.runtime.view("AMD", "test").getAsJsonArray("premarketOrders").size());
            h.runtime.dispatch(json("{\"symbol\":\"AMD\",\"keyCode\":\"Digit1\",\"price\":99.5,\"source\":\"bookmap_chart_hotkey\"}"));
            assertEquals(List.of("123"), h.replaced, h.logs.toString()); assertEquals(2, h.posted.size(), "Only the canceled order's remaining quantity is resubmitted");
            JsonObject replacement = h.brokerOrders.get("124");
            assertEquals("SEAMLESS", replacement.get("session").getAsString()); assertEquals("LIMIT", replacement.get("orderType").getAsString());
            assertEquals(99.5, replacement.get("price").getAsDouble());
            JsonObject label = h.runtime.view("AMD", "test").getAsJsonArray("premarketOrders").get(0).getAsJsonObject();
            assertEquals(1, label.get("pairIndex").getAsInt()); assertEquals("124", label.get("orderID").getAsString());
        }
    }
    @Test void afterHoursOrdersWorkWithoutAutomaticProtectionClosingThePosition() {
        try (Harness h = new Harness()) {
            h.now.set(Instant.parse("2026-11-24T00:15:00Z").toEpochMilli()); h.enabled = true; h.runtime.start().join();
            for (int i = 0; i < 4; i++) { h.now.addAndGet(11000); h.runtime.refreshAccount(); }
            assertTrue(h.posted.isEmpty()); assertTrue(h.logs.stream().noneMatch(log -> log.contains("without stop loss")));
            h.runtime.dispatch(PremarketTradingPanel.fixedAction("AMD", "sell_limit", "3", "101"));
            assertEquals(1, h.posted.size(), h.logs.toString()); assertEquals("LIMIT", h.posted.get(0).get("orderType").getAsString());
        }
    }
}
