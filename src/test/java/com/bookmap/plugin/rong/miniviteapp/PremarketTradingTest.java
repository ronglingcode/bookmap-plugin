package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.runtime.PremarketTrading;
import com.bookmap.plugin.rong.tradebuttons.PremarketTradingPanel;
import com.google.gson.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PremarketTradingTest {
    @Test void lostPostAllowsCancelAllAndResumesWithoutRestartAfterMatchingBrokerObservation() {
        try (Harness h = new Harness()) {
            h.failPost = true; h.trading.dispatch(action("buy_now", 5));
            h.trading.dispatch(action("buy_now", 5)); assertEquals(1, h.posted.size());
            h.trading.dispatch(action("cancel_all", 0));
            assertTrue(h.logs.stream().anyMatch(log -> log.contains("previous submission remains unconfirmed")));
            assertFalse(h.trading.isBusy("AMD")); assertEquals(1, h.posted.size());
            JsonObject observed = h.posted.get(0).deepCopy(); observed.addProperty("orderId", "456"); observed.addProperty("status", "FILLED");
            observed.addProperty("enteredTime", Instant.ofEpochMilli(h.now.get()).toString());
            observed.addProperty("quantity", 5); observed.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().remove("quantity");
            h.orders.add(observed); h.trading.observeOrders(h.orders);
            h.failPost = false; h.net = 5; h.trading.dispatch(action("sell_now", 5));
            assertEquals(2, h.posted.size(), h.logs.toString()); assertFalse(h.trading.isBusy("AMD"));
        }
    }
    @Test void failedCancelCanBeReconciledByNextExplicitActionWithoutRestart() {
        try (Harness h = new Harness()) {
            h.working(); h.failCancel = true; h.trading.dispatch(action("cancel_all", 0));
            assertEquals(1, h.canceled.size()); assertFalse(h.trading.isBusy("AMD"));
            h.failCancel = false; h.confirm = true; h.trading.dispatch(action("cancel_all", 0));
            assertEquals(2, h.canceled.size()); assertFalse(h.trading.isBusy("AMD")); assertTrue(h.posted.isEmpty());
        }
    }
    static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    static JsonObject action(String operation, int quantity) { return PremarketTradingPanel.action("AMD", operation, "" + quantity, "1"); }
    static class Harness implements AutoCloseable, PremarketTrading.Broker {
        final AtomicLong now = new AtomicLong(Instant.parse("2026-11-23T14:00:00Z").toEpochMilli());
        final List<JsonObject> posted = new ArrayList<>();
        final List<String> canceled = new ArrayList<>(), logs = new ArrayList<>();
        final List<Runnable> delayed = new ArrayList<>();
        JsonArray orders = new JsonArray(); double net; int positionReads; boolean enabled = true, failPost, failCancel, confirm, recentWorking;
        JsonObject quote = json("{\"bid\":100,\"ask\":101}");
        final PremarketTrading trading = new PremarketTrading(this, Runnable::run, (delay, task) -> { delayed.add(task); return () -> {}; },
                now::get, () -> enabled, symbol -> quote, (symbol, message) -> logs.add(message), () -> {});
        public JsonArray orders() { return orders.deepCopy(); }
        public JsonObject order(String id) {
            if (id.equals("123") && !recentWorking) return json("{\"status\":\"FILLED\"}");
            if (confirm) { orders = new JsonArray(); net = 7; }
            return json("{\"status\":\"" + (confirm ? "CANCELED" : "PENDING_CANCEL") + "\"}");
        }
        public double position(String symbol) { positionReads++; return net; }
        public void cancel(String id) throws Exception { canceled.add(id); if (failCancel) throw new Exception("cancel response lost"); }
        public String submit(JsonObject order) throws Exception { posted.add(order.deepCopy()); if (failPost) throw new Exception("response lost"); return "123"; }
        public String replace(String id, JsonObject order) throws Exception { posted.add(order.deepCopy()); if (failPost) throw new Exception("replace response lost"); return "124"; }
        void working() { orders.add(json("{\"orderId\":45,\"status\":\"WORKING\",\"orderLegCollection\":[{\"instrument\":{\"symbol\":\"AMD\"}}]}")); }
        public void close() { trading.close(); }
    }
    @Test void everyButtonUsesExtendedLimitWithPositionAwareInstructionAndOnePercentOffset() {
        for (double net : new double[]{0, 10, -10}) for (String operation : List.of("buy_now", "buy_bid", "sell_now", "sell_ask")) {
            try (Harness h = new Harness()) {
                h.net = net; assertTrue(h.trading.dispatch(action(operation, 5)));
                assertEquals(1, h.positionReads); assertEquals(1, h.posted.size(), h.logs.toString());
                JsonObject order = h.posted.get(0), leg = order.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject();
                boolean buy = operation.startsWith("buy");
                assertEquals(buy ? net < 0 ? "BUY_TO_COVER" : "BUY" : net > 0 ? "SELL" : "SELL_SHORT", leg.get("instruction").getAsString());
                assertEquals(5, leg.get("quantity").getAsInt());
                assertEquals("LIMIT", order.get("orderType").getAsString());
                assertEquals("SEAMLESS", order.get("session").getAsString()); assertEquals("DAY", order.get("duration").getAsString());
                assertFalse(order.has("stopPrice")); assertFalse(order.has("childOrderStrategies"));
                assertEquals(operation.equals("buy_now") ? 102.01 : operation.equals("buy_bid") ? 100 : operation.equals("sell_now") ? 99 : 101,
                        order.get("price").getAsDouble(), .000001);
            }
        }
    }
    @Test void flattenUsesFullLongOrShortPositionAndIgnoresTextboxQuantity() {
        for (double net : new double[]{10, -10}) try (Harness h = new Harness()) {
            h.net = net; h.trading.dispatch(action("flatten", 0));
            JsonObject order = h.posted.get(0), leg = order.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject();
            assertEquals(10, leg.get("quantity").getAsInt());
            assertEquals(net > 0 ? "SELL" : "BUY_TO_COVER", leg.get("instruction").getAsString());
            assertEquals(net > 0 ? 99 : 102.01, order.get("price").getAsDouble(), .000001);
        }
    }
    @Test void flattenWaitsForCancellationAndUsesPositionAfterConcurrentFill() {
        try (Harness h = new Harness()) {
            h.net = 10; h.working(); h.trading.dispatch(action("flatten", 99));
            assertEquals(List.of("45"), h.canceled); assertTrue(h.posted.isEmpty()); assertEquals(0, h.positionReads);
            assertTrue(h.trading.isBusy("AMD"));
            h.trading.dispatch(action("buy_now", 1)); assertTrue(h.posted.isEmpty());
            h.confirm = true; h.delayed.remove(0).run();
            assertEquals(7, h.posted.get(0).getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("quantity").getAsInt());
            assertFalse(h.trading.isBusy("AMD"));
        }
    }
    @Test void noMarketFallbackOnPartialFillAndSecondFlattenClosesOnlyRemainingShares() {
        try (Harness h = new Harness()) {
            h.net = 10; h.trading.dispatch(action("flatten", 0));
            h.working(); h.net = 7; h.trading.dispatch(action("flatten", 0));
            h.confirm = true; h.delayed.remove(0).run();
            assertEquals(2, h.posted.size());
            assertTrue(h.posted.stream().allMatch(order -> order.get("orderType").getAsString().equals("LIMIT")));
            assertEquals(7, h.posted.get(1).getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("quantity").getAsInt());
        }
    }
    @Test void invalidInputsDisabledToggleClosedSessionAndOversizedCloseNeverSubmit() {
        assertThrows(IllegalArgumentException.class, () -> PremarketTradingPanel.action("AMD", "buy_now", "0", "1"));
        assertThrows(IllegalArgumentException.class, () -> PremarketTradingPanel.action("AMD", "buy_now", "1.5", "1"));
        assertThrows(IllegalArgumentException.class, () -> PremarketTradingPanel.action("AMD", "buy_now", "1", "NaN"));
        assertDoesNotThrow(() -> PremarketTradingPanel.action("AMD", "flatten", "garbage", "1"));
        try (Harness h = new Harness()) {
            h.enabled = false; h.trading.dispatch(action("buy_now", 1)); assertTrue(h.posted.isEmpty());
            h.enabled = true; h.now.set(Instant.parse("2026-11-23T14:30:00Z").toEpochMilli());
            h.trading.dispatch(action("buy_now", 1)); assertTrue(h.posted.isEmpty());
            h.now.addAndGet(-60000); h.net = -3; h.trading.dispatch(action("buy_now", 5)); assertTrue(h.posted.isEmpty());
            h.net = 0; h.quote = new JsonObject(); h.trading.dispatch(action("buy_now", 1)); assertTrue(h.posted.isEmpty());
        }
    }
    @Test void cancellationFailureAndUncertainPostNeverRetryOrPermitDuplicateSubmission() {
        try (Harness h = new Harness()) {
            h.failPost = true; h.trading.dispatch(action("buy_now", 5)); h.trading.dispatch(action("buy_now", 5));
            assertEquals(1, h.posted.size()); assertTrue(h.logs.stream().anyMatch(log -> log.contains("outcome uncertain")));
        }
        try (Harness h = new Harness()) {
            h.working(); h.failCancel = true; h.trading.dispatch(action("flatten", 0));
            assertTrue(h.posted.isEmpty()); assertEquals(1, h.canceled.size());
        }
    }
    @Test void newlyAcceptedOrderMissingFromListStillBlocksDuplicatesAndIsCanceledByFlatten() {
        try (Harness h = new Harness()) {
            h.recentWorking = true; h.trading.dispatch(action("buy_now", 5));
            h.trading.dispatch(action("buy_now", 5)); assertEquals(1, h.posted.size());
            h.trading.dispatch(action("flatten", 0)); assertEquals(List.of("123"), h.canceled);
            assertEquals(1, h.posted.size()); h.confirm = true; h.delayed.remove(0).run();
            assertEquals(2, h.posted.size());
        }
    }
    @Test void workingOrderBlocksAdditionalOrderAndFlatFlattenOnlyCancels() {
        try (Harness h = new Harness()) {
            h.working(); h.trading.dispatch(action("buy_now", 5)); assertTrue(h.posted.isEmpty());
            h.orders = new JsonArray(); h.trading.dispatch(action("flatten", 0)); assertTrue(h.posted.isEmpty());
        }
    }
    @Test void cancellationTimeoutOrToggleOffMidCancelCannotSubmit() {
        try (Harness h = new Harness()) {
            h.working(); h.net = 10; h.trading.dispatch(action("flatten", 0));
            for (int i = 0; i < 20; i++) h.delayed.remove(0).run();
            assertTrue(h.posted.isEmpty()); assertFalse(h.trading.isBusy("AMD"));
        }
        try (Harness h = new Harness()) {
            h.working(); h.net = 10; h.trading.dispatch(action("flatten", 0));
            h.enabled = false; h.confirm = true; h.delayed.remove(0).run(); assertTrue(h.posted.isEmpty());
        }
    }
    @Test void outwardRoundingAndSubDollarLimits() {
        try (Harness h = new Harness()) {
            h.quote = json("{\"bid\":0.5011,\"ask\":0.5012}"); h.trading.dispatch(action("buy_now", 1));
            assertEquals(.5063, h.posted.get(0).get("price").getAsDouble(), .0000001);
            h.trading.dispatch(action("sell_now", 1)); assertEquals(.496, h.posted.get(1).get("price").getAsDouble(), .0000001);
        }
    }
    @Test void explicitFixedPricesDoNotUseOffsetOrRequireQuotes() {
        for (String operation : List.of("buy_limit", "sell_limit")) for (double net : new double[]{0, 10, -10}) try (Harness h = new Harness()) {
            h.quote = new JsonObject(); h.net = net;
            h.trading.dispatch(PremarketTradingPanel.fixedAction("AMD", operation, "3", "98.25"));
            assertEquals(1, h.posted.size(), h.logs.toString()); JsonObject order = h.posted.get(0);
            assertEquals(98.25, order.get("price").getAsDouble());
            String instruction = order.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("instruction").getAsString();
            assertEquals(operation.equals("buy_limit") ? net < 0 ? "BUY_TO_COVER" : "BUY" : net > 0 ? "SELL" : "SELL_SHORT", instruction);
        }
    }
    @Test void afterHoursButtonsSubmitLimitsAndStopAtEightEastern() {
        for (String operation : List.of("buy_now", "buy_bid", "sell_now", "sell_ask", "flatten")) try (Harness h = new Harness()) {
            h.now.set(Instant.parse("2026-11-24T00:15:00Z").toEpochMilli()); h.net = operation.equals("flatten") ? 10 : 0;
            h.trading.dispatch(action(operation, 3)); assertEquals(1, h.posted.size(), h.logs.toString());
            assertEquals("LIMIT", h.posted.get(0).get("orderType").getAsString());
            h.now.set(Instant.parse("2026-11-24T01:00:00Z").toEpochMilli()); h.trading.dispatch(action(operation, 3)); assertEquals(1, h.posted.size());
        }
    }
    @Test void cancelAllIgnoresQuantityAndOffsetAndNeverPlacesAClosingOrder() {
        try (Harness h = new Harness()) {
            h.now.set(Instant.parse("2026-11-24T02:00:00Z").toEpochMilli());
            h.net = 10; h.working();
            h.trading.dispatch(PremarketTradingPanel.action("AMD", "cancel_all", "invalid", "invalid"));
            assertEquals(List.of("45"), h.canceled); assertTrue(h.posted.isEmpty());
            h.confirm = true; h.delayed.remove(0).run();
            assertTrue(h.posted.isEmpty()); assertEquals(0, h.positionReads);
            assertFalse(h.trading.isBusy("AMD")); assertTrue(h.logs.stream().anyMatch(log -> log.contains("Cancel All: cancellation confirmed")));
        }
    }
    @Test void hoverLimitChoosesSideAtMidpointWithExactPriceAndCurrentQuantity() {
        for (double price : new double[]{99, 102}) try (Harness h = new Harness()) {
            h.trading.dispatch(PremarketTradingPanel.fixedAction("AMD", "hover_limit", "7", "" + price));
            assertEquals(1, h.posted.size(), h.logs.toString()); JsonObject order = h.posted.get(0);
            assertEquals(price, order.get("price").getAsDouble());
            assertEquals(7, order.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("quantity").getAsInt());
            assertEquals(price > 100.5 ? "SELL_SHORT" : "BUY", order.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("instruction").getAsString());
        }
        try (Harness h = new Harness()) {
            h.trading.dispatch(PremarketTradingPanel.fixedAction("AMD", "hover_limit", "7", "100.5")); assertTrue(h.posted.isEmpty());
            h.quote = new JsonObject(); h.trading.dispatch(PremarketTradingPanel.fixedAction("AMD", "hover_limit", "7", "99")); assertTrue(h.posted.isEmpty());
            assertThrows(IllegalArgumentException.class, () -> PremarketTradingPanel.fixedAction("AMD", "buy_limit", "7", "99.001")); assertTrue(h.posted.isEmpty());
        }
    }
}
