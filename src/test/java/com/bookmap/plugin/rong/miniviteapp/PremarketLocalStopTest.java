package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.runtime.*;
import com.bookmap.plugin.rong.tradebuttons.PremarketTradingPanel;
import com.google.gson.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PremarketLocalStopTest {
    @Test void triggeredStopWaitsForUnknownEntryThenExitsOnceAfterBrokerConfirmation() {
        try (Harness h = new Harness()) {
            h.select(99); h.lostPost = true; h.entry(true);
            assertEquals(1, h.posted.size());
            h.net = 10; h.account(); h.trade(98.99);
            assertEquals(1, h.posted.size(), "An unconfirmed entry must not cause a blind closing submission");
            assertTrue(h.trading.stopView("AMD").get("status").getAsString().startsWith("EXIT BLOCKED"));
            h.remember("1", h.posted.get(0));
            h.orders.get("1").addProperty("status", "FILLED");
            h.orders.get("1").addProperty("enteredTime", Instant.ofEpochMilli(h.now.get()).toString());
            h.lostPost = false; h.trading.observeOrders(h.orders()); h.account();
            assertEquals(2, h.posted.size(), h.logs.toString());
            JsonObject closing = h.posted.get(1).getAsJsonArray("orderLegCollection").get(0).getAsJsonObject();
            assertEquals("SELL", closing.get("instruction").getAsString()); assertEquals(10, closing.get("quantity").getAsInt());
            h.trade(98); h.account(); assertEquals(2, h.posted.size(), "Accepted exit must not be duplicated");
        }
    }
    static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    static class Harness implements AutoCloseable, PremarketTrading.Broker {
        final AtomicLong now = new AtomicLong(Instant.parse("2026-11-23T14:00:00Z").toEpochMilli());
        final Map<String, JsonObject> orders = new LinkedHashMap<>();
        final List<JsonObject> posted = new ArrayList<>();
        final List<String> canceled = new ArrayList<>(), logs = new ArrayList<>();
        final List<Runnable> delayed = new ArrayList<>();
        boolean enabled = true, confirmCancel = true, lostPost, rejectCancel;
        double net, fillDuringCancel;
        JsonObject quote = json("{\"bid\":100,\"ask\":100.02}");
        final PremarketTrading trading = new PremarketTrading(this, Runnable::run,
                (delay, task) -> { delayed.add(task); return () -> {}; }, now::get, () -> enabled,
                symbol -> quote, (symbol, message) -> logs.add(message), () -> {});
        public JsonArray orders() { JsonArray result = new JsonArray(); orders.values().forEach(o -> result.add(o.deepCopy())); return result; }
        public JsonObject order(String id) { return orders.get(id).deepCopy(); }
        public double position(String symbol) { return net; }
        public void cancel(String id) {
            canceled.add(id);
            if (rejectCancel) throw new PremarketTrading.RejectedOrderException("cancel rejected");
            orders.get(id).addProperty("status", confirmCancel ? "CANCELED" : "PENDING_CANCEL");
            net += fillDuringCancel; fillDuringCancel = 0;
        }
        public String submit(JsonObject payload) throws Exception {
            posted.add(payload.deepCopy());
            if (lostPost) throw new Exception("lost response");
            String id = Integer.toString(100 + posted.size()); remember(id, payload); return id;
        }
        void remember(String id, JsonObject payload) {
            JsonObject raw = payload.deepCopy(); raw.addProperty("orderId", id); raw.addProperty("status", "WORKING");
            raw.addProperty("quantity", payload.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("quantity").getAsDouble());
            orders.put(id, raw);
        }
        void working(String id, boolean buy) { remember(id, PremarketTrading.limitOrder("AMD", 5, 101, buy, false)); }
        void select(double price) { trading.selectStop("AMD", price, net); }
        void trade(double price) { trading.observeTrade("AMD", price, now.get()); }
        void account() { JsonObject positions = new JsonObject(); positions.add("AMD", json("{\"netQuantity\":" + net + "}")); trading.observePositions(positions, orders(), now.get()); }
        void entry(boolean buy) { trading.dispatch(PremarketTradingPanel.action("AMD", buy ? "buy_bid" : "sell_ask", "10", "1")); }
        public void close() { trading.close(); }
    }
    @Test void openingNeedsSelectedStopOnCorrectSideButClosingRemainsAvailable() {
        try (Harness h = new Harness()) {
            h.entry(true); assertTrue(h.posted.isEmpty()); assertTrue(h.logs.stream().anyMatch(s -> s.contains("hover + Z")));
            h.select(101); h.entry(true); assertTrue(h.posted.isEmpty());
            h.select(99); h.entry(true); assertEquals(1, h.posted.size());
        }
        try (Harness h = new Harness()) {
            h.net = 10; h.trading.dispatch(PremarketTradingPanel.action("AMD", "sell_now", "10", "1")); assertEquals(1, h.posted.size());
        }
    }
    @Test void longAndShortTriggerAtExactlyOneCentAndUseOppositeSideClosingLimitsOnce() {
        for (boolean longSide : new boolean[]{true, false}) try (Harness h = new Harness()) {
            h.net = longSide ? 10 : -10; h.select(100);
            h.trade(longSide ? 100 : 100); h.trade(longSide ? 99.991 : 100.009); assertTrue(h.posted.isEmpty());
            h.trade(longSide ? 99.99 : 100.01); assertEquals(1, h.posted.size(), h.logs.toString());
            JsonObject order = h.posted.get(0), leg = order.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject();
            assertEquals(longSide ? "SELL" : "BUY_TO_COVER", leg.get("instruction").getAsString());
            assertEquals(longSide ? 99.99 : 100.03, order.get("price").getAsDouble(), .000001);
            assertEquals("LIMIT", order.get("orderType").getAsString()); assertEquals("SEAMLESS", order.get("session").getAsString());
            assertEquals(10, leg.get("quantity").getAsInt()); assertFalse(order.has("stopPrice"));
            for (int i = 0; i < 5; i++) h.trade(longSide ? 99 : 101);
            assertEquals(1, h.posted.size(), "Triggered stop must never submit duplicates or chase its unfilled order");
        }
    }
    @Test void cancellationMustBeConfirmedAndFillDuringCancellationChangesExitQuantity() {
        try (Harness h = new Harness()) {
            h.net = 10; h.select(100); h.working("1", true); h.working("2", false);
            h.confirmCancel = false; h.fillDuringCancel = 3; h.trade(99.99);
            assertEquals(List.of("1", "2"), h.canceled); assertTrue(h.posted.isEmpty());
            assertTrue(h.trading.isBusy("AMD")); h.orders.values().forEach(o -> o.addProperty("status", "CANCELED"));
            h.delayed.remove(0).run(); assertEquals(13, h.posted.get(0).getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("quantity").getAsInt());
        }
    }
    @Test void aStopSelectedBeforeEntryArmsOnPartialFillAndUsesTheLatestLivePrint() {
        try (Harness h = new Harness()) {
            h.select(99); h.trade(98.99); assertTrue(h.posted.isEmpty(), "Selection alone cannot exit");
            h.entry(true); h.net = 4; h.trade(98.99);
            assertEquals(2, h.posted.size(), h.logs.toString()); assertEquals(List.of("101"), h.canceled);
            assertEquals(4, h.posted.get(1).getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("quantity").getAsInt());
        }
    }
    @Test void breachCancelsAnAcceptedEntryEvenBeforeTheFirstAccountFillRefresh() {
        try (Harness h = new Harness()) {
            h.select(99); h.entry(true); h.trade(98.99);
            assertEquals(List.of("101"), h.canceled); assertEquals(1, h.posted.size(), "Cancel pending entry, then broker confirms flat; no closing order");
        }
    }
    @Test void afterHoursHasTheSameStopBehaviorAsPremarket() {
        for (boolean longSide : new boolean[]{true, false}) try (Harness h = new Harness()) {
            h.now.set(Instant.parse("2026-11-24T00:15:00Z").toEpochMilli()); h.net = longSide ? 10 : -10;
            h.select(100); h.trade(longSide ? 99.99 : 100.01);
            assertEquals(1, h.posted.size()); assertEquals("SEAMLESS", h.posted.get(0).get("session").getAsString());
        }
    }
    @Test void pennyExitArithmeticDoesNotRoundAnExtraCentPastTheQuote() {
        for (boolean longSide : new boolean[]{true, false}) try (Harness h = new Harness()) {
            h.quote = json("{\"bid\":100.10,\"ask\":101.01}"); h.net = longSide ? 10 : -10;
            h.select(100); h.trade(longSide ? 99.99 : 100.01);
            assertEquals(longSide ? 100.09 : 101.02, h.posted.get(0).get("price").getAsDouble(), .0000001);
        }
    }
    @Test void stopBreachPreemptsAnOrderReplacementAndExitsAfterItsCancellationCompletes() {
        try (Harness h = new Harness()) {
            h.select(99); h.entry(true); h.net = 4; h.now.incrementAndGet(); h.account();
            h.confirmCancel = false;
            h.trading.chartDigit(json("{\"symbol\":\"AMD\",\"keyCode\":\"Digit1\",\"price\":99.5}"));
            h.trade(98.99); assertEquals(1, h.posted.size());
            h.orders.get("101").addProperty("status", "CANCELED"); h.delayed.remove(0).run();
            assertEquals(2, h.posted.size(), h.logs.toString());
            JsonObject leg = h.posted.get(1).getAsJsonArray("orderLegCollection").get(0).getAsJsonObject();
            assertEquals("SELL", leg.get("instruction").getAsString()); assertEquals(4, leg.get("quantity").getAsInt());
        }
    }
    @Test void delayedManualReplacementCannotSubmitAfter928Handover() {
        try (Harness h = new Harness()) {
            h.select(99); h.entry(true); h.confirmCancel = false;
            h.trading.chartDigit(json("{\"symbol\":\"AMD\",\"keyCode\":\"Digit1\",\"price\":99.5}"));
            h.now.set(Instant.parse("2026-11-23T14:28:00Z").toEpochMilli());
            h.orders.get("101").addProperty("status", "CANCELED"); h.delayed.remove(0).run();
            assertEquals(1, h.posted.size()); assertFalse(h.trading.isBusy("AMD"));
        }
    }
    @Test void zAdjustmentsPersistAndApplyToTheHeldPosition() {
        try (Harness h = new Harness()) {
            h.net = 10; h.select(98); h.now.addAndGet(60000); h.trade(99); assertTrue(h.posted.isEmpty());
            assertEquals(98, h.trading.stopView("AMD").get("price").getAsDouble());
            h.select(99.50); h.trade(99.49); assertEquals(1, h.posted.size());
            assertThrows(IllegalArgumentException.class, () -> h.select(98), "Triggered exit cannot be silently rearmed");
        }
    }
    @Test void staleFutureAndOtherSymbolPrintsNeverTriggerTheStop() {
        try (Harness h = new Harness()) {
            h.net = 10; h.select(100);
            h.trading.observeTrade("AMD", 99, h.now.get() - 3000);
            h.trading.observeTrade("AMD", 99, h.now.get() + 2000);
            h.trading.observeTrade("MSFT", 99, h.now.get()); assertTrue(h.posted.isEmpty());
            h.trade(99.99); assertEquals(1, h.posted.size());
        }
    }
    @Test void positionDirectionChangeDoesNotCloseAnUnrelatedOppositeTrade() {
        try (Harness h = new Harness()) {
            h.net = 10; h.select(100); h.net = -5; h.trade(99.99);
            assertTrue(h.posted.isEmpty()); assertTrue(h.logs.stream().anyMatch(s -> s.contains("position direction changed")));
        }
    }
    @Test void cancellationFailureOrUncertainSubmissionNeverCausesAnAutomaticRetry() {
        for (boolean cancelFailure : new boolean[]{true, false}) try (Harness h = new Harness()) {
            h.net = 10; h.select(100); h.rejectCancel = cancelFailure; h.lostPost = !cancelFailure;
            if (cancelFailure) h.working("1", true);
            h.trade(99.99); h.trade(99); h.trade(98);
            assertEquals(cancelFailure ? 0 : 1, h.posted.size());
            assertTrue(h.trading.stopView("AMD").get("status").getAsString().startsWith("EXIT STOPPED"));
        }
    }
    @Test void canceledStopExitCannotSubmitAfter928AndRegularDigitsAreNotIntercepted() {
        try (Harness h = new Harness()) {
            h.net = 10; h.select(100); h.working("1", true); h.confirmCancel = false; h.trade(99.99);
            h.now.set(Instant.parse("2026-11-23T14:28:00Z").toEpochMilli());
            h.orders.get("1").addProperty("status", "CANCELED"); h.delayed.remove(0).run();
            h.trade(98); assertTrue(h.posted.isEmpty()); assertEquals(0, h.trading.stopView("AMD").size());
            assertFalse(h.trading.chartDigit(json("{\"symbol\":\"AMD\",\"keyCode\":\"Digit1\",\"price\":99,\"premarketOrderId\":\"1\"}")));
            h.trading.dispatch(json("{\"type\":\"premarket_adjust\",\"symbol\":\"AMD\",\"orderId\":\"1\",\"price\":99}")); assertTrue(h.posted.isEmpty());
        }
    }
    @Test void sessionEndFlatPositionAndDisabledFeatureRemoveTheLocalStop() {
        try (Harness h = new Harness()) {
            h.net = 10; h.select(100); h.net = 0; h.now.incrementAndGet(); h.account(); assertEquals(0, h.trading.stopView("AMD").size());
            h.select(99); h.enabled = false; h.trade(98); assertEquals(0, h.trading.stopView("AMD").size()); assertTrue(h.posted.isEmpty());
            h.enabled = true; h.select(99); h.now.set(Instant.parse("2026-11-23T21:00:00Z").toEpochMilli());
            assertEquals(0, h.trading.stopView("AMD").size(), "Morning stop must not be reused after hours");
        }
    }
}
