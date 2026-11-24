package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.*;
import com.bookmap.plugin.rong.pricelines.*;
import com.bookmap.plugin.rong.miniviteapp.runtime.*;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.NativeViews;
import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.AccountProjection;
import com.bookmap.plugin.rong.tradebuttons.PremarketTradingPanel;
import com.google.gson.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PremarketOrderAdjustmentTest {
    static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    static class Harness implements AutoCloseable, PremarketTrading.Broker {
        final Map<String, JsonObject> raw = new LinkedHashMap<>();
        final List<JsonObject> replacements = new ArrayList<>(); final List<String> logs = new ArrayList<>();
        final PremarketTrading trader; int sequence; double net; boolean failReplace, fillOnCancel, rejectCancelAfterFill; String canceledForEdit;
        Harness() {
            trader = new PremarketTrading(this, Runnable::run, (delay, task) -> () -> {},
                    () -> Instant.parse("2026-11-23T14:00:00Z").toEpochMilli(), () -> true,
                    symbol -> json("{\"bid\":100,\"ask\":101}"), (symbol, message) -> logs.add(message), () -> traderObserve());
        }
        void traderObserve() { trader.observeOrders(orders()); }
        public JsonArray orders() { JsonArray all = new JsonArray(); raw.values().forEach(order -> all.add(order.deepCopy())); return all; }
        public JsonObject order(String id) { return raw.get(id).deepCopy(); }
        public double position(String symbol) { return net; }
        public void cancel(String id) throws Exception {
            raw.get(id).addProperty("status", "CANCELED"); canceledForEdit = id;
            if (fillOnCancel) { raw.get(id).addProperty("filledQuantity", 4); net += 4; }
            if (rejectCancelAfterFill) {
                raw.get(id).addProperty("status", "FILLED"); raw.get(id).addProperty("filledQuantity", 10); net = 10;
                throw new PremarketTrading.RejectedOrderException("Already filled");
            }
        }
        public String submit(JsonObject payload) throws Exception {
            if (canceledForEdit != null) { canceledForEdit = null; replacements.add(payload.deepCopy()); if (failReplace) throw new Exception("response lost"); }
            String id = "" + ++sequence; JsonObject order = payload.deepCopy();
            order.addProperty("orderId", id); order.addProperty("status", "WORKING");
            order.addProperty("quantity", payload.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("quantity").getAsDouble());
            raw.put(id, order); return id;
        }
        public String replace(String id, JsonObject payload) throws Exception {
            replacements.add(payload.deepCopy()); if (failReplace) throw new Exception("response lost");
            raw.get(id).addProperty("status", "REPLACED"); return submit(payload);
        }
        void buy(int quantity) { trader.dispatch(PremarketTradingPanel.action("AMD", "buy_bid", "" + quantity, "1")); }
        boolean digit(int index, double price) {
            JsonObject key = json("{\"symbol\":\"AMD\",\"source\":\"bookmap_chart_hotkey\"}"); key.addProperty("keyCode", "Digit" + index); key.addProperty("price", price);
            return trader.chartDigit(key);
        }
        public void close() { trader.close(); }
    }
    @Test void threeRestingEntriesHaveStableNumbersAndKeysMoveExactOrderWhileFlatWithoutPlan() {
        try (Harness h = new Harness()) {
            h.buy(10); h.buy(20); h.buy(30);
            JsonArray labels = h.trader.ordersForChart("AMD"); assertEquals(3, labels.size(), h.logs.toString());
            assertEquals(2, labels.get(1).getAsJsonObject().get("pairIndex").getAsInt());
            assertTrue(h.digit(2, 99.55)); assertEquals(1, h.replacements.size());
            assertEquals("CANCELED", h.raw.get("2").get("status").getAsString());
            assertEquals(99.55, h.raw.get("4").get("price").getAsDouble());
            assertEquals(20, h.raw.get("4").get("quantity").getAsInt());
            assertEquals("2", h.trader.ordersForChart("AMD").get(1).getAsJsonObject().get("pairIndex").getAsString());
            assertEquals("4", h.trader.ordersForChart("AMD").get(1).getAsJsonObject().get("orderID").getAsString());
            assertTrue(h.digit(2, 99.25)); assertEquals("CANCELED", h.raw.get("4").get("status").getAsString());
            assertEquals(99.25, h.raw.get("5").get("price").getAsDouble());
            assertEquals("WORKING", h.raw.get("1").get("status").getAsString());
            assertEquals("WORKING", h.raw.get("3").get("status").getAsString());
            assertTrue(h.replacements.stream().allMatch(order -> order.get("session").getAsString().equals("SEAMLESS") && order.get("orderType").getAsString().equals("LIMIT")));
        }
    }
    @Test void closedOrderNumberNeverBecomesAnEntryAndOtherNumbersDoNotShift() {
        try (Harness h = new Harness()) {
            h.buy(10); h.buy(20); h.buy(30); h.raw.get("1").addProperty("status", "FILLED"); h.traderObserve();
            assertTrue(h.digit(1, 99)); assertTrue(h.replacements.isEmpty()); assertEquals(3, h.sequence);
            assertTrue(h.digit(3, 98)); assertEquals(30, h.raw.get("4").get("quantity").getAsInt());
        }
    }
    @Test void multipleClosingOrdersReserveSharesAndRepricingPreservesOnlyRemainingQuantity() {
        try (Harness h = new Harness()) {
            h.net = -100; h.buy(30); h.buy(40); h.buy(40); assertEquals(2, h.sequence);
            h.raw.get("1").addProperty("filledQuantity", 10); h.net = -90; h.traderObserve();
            assertTrue(h.digit(1, 99)); JsonObject order = h.replacements.get(0);
            assertEquals(20, order.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("quantity").getAsInt());
            assertEquals("BUY_TO_COVER", order.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("instruction").getAsString());
        }
    }
    @Test void lostReplaceResponseDoesNotRetryAndBlocksAnotherEdit() {
        try (Harness h = new Harness()) {
            h.buy(10); h.failReplace = true; h.digit(1, 99); h.digit(1, 98);
            assertEquals(1, h.replacements.size()); assertEquals(1, h.sequence);
        }
    }
    @Test void fillDuringCancellationOnlyResubmitsFinalRemainingQuantity() {
        try (Harness h = new Harness()) {
            h.buy(10); h.fillOnCancel = true; h.digit(1, 99);
            assertEquals(6, h.raw.get("2").get("quantity").getAsInt(), h.logs.toString());
            assertEquals(1, h.trader.ordersForChart("AMD").get(0).getAsJsonObject().get("pairIndex").getAsInt());
        }
    }
    @Test void flattenReconcilesAlreadyFilledCancellationAndDoesNotLockFutureActions() {
        try (Harness h = new Harness()) {
            h.buy(10); h.rejectCancelAfterFill = true;
            h.trader.dispatch(PremarketTradingPanel.action("AMD", "flatten", "", "1"));
            assertEquals(2, h.sequence, h.logs.toString());
            assertEquals("SELL", h.raw.get("2").getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("instruction").getAsString());
            assertFalse(h.logs.stream().anyMatch(log -> log.contains("outcome uncertain")));
        }
    }
    @Test void finishedNumbersAreReusedAndCapturedOldOrderIdCannotMoveTheNewOrder() {
        try (Harness h = new Harness()) {
            for (int i = 0; i < 12; i++) {
                h.buy(10); assertEquals(1, h.trader.ordersForChart("AMD").get(0).getAsJsonObject().get("pairIndex").getAsInt());
                h.raw.get("" + h.sequence).addProperty("status", "FILLED"); h.traderObserve();
            }
            h.buy(10);
            JsonObject staleKey = json("{\"symbol\":\"AMD\",\"keyCode\":\"Digit1\",\"price\":99,\"premarketOrderId\":\"12\"}");
            assertTrue(h.trader.chartDigit(staleKey)); assertEquals(13, h.sequence); assertTrue(h.replacements.isEmpty());
            assertEquals("WORKING", h.raw.get("13").get("status").getAsString());
        }
    }
    @Test void chartPipelineLabelsBothOpeningAndClosingLimitsWithoutDuplicateExitLines() {
        try (Harness h = new Harness()) {
            h.net = -100; h.buy(10); h.buy(20); h.buy(30);
            JsonObject account = AccountProjection.projectAccount(json("{\"currentBalances\":{\"liquidationValue\":25000}}"), h.orders(), "2026-11-23", symbol -> 100);
            JsonObject view = json("{\"type\":\"account_ready\",\"symbol\":\"AMD\",\"timestamp\":1795442400000}");
            view.add("account", account); view.add("premarketOrders", h.trader.ordersForChart("AMD"));
            PriceLineStore lines = new PriceLineStore(); PendingEntryOrderManager entries = new PendingEntryOrderManager(lines);
            ExitOrderManager exits = new ExitOrderManager(lines); entries.onInstrumentInitialized("AMD", .01); exits.onInstrumentInitialized("AMD", .01);
            SignalWebSocketServer server = new SignalWebSocketServer(0, 97);
            try {
                server.registerAccountStateListener(entries); server.registerExitOrderPairsConfigListener(exits);
                NativeViews.project(view).forEach(server::acceptLocalMessage);
                List<PriceLine> rendered = lines.getLines("AMD");
                assertEquals(1, rendered.size(), "Equal-price lines aggregate without duplicate legacy exits");
                assertTrue(rendered.get(0).getCustomLabel().contains("1:PM BUY LIMIT"));
                assertTrue(rendered.get(0).getCustomLabel().contains("2:PM BUY LIMIT"));
                assertTrue(rendered.get(0).getCustomLabel().contains("3:PM BUY LIMIT"));
            } finally { server.shutdown(); }
        }
    }
}
