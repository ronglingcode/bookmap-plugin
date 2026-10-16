package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.controllers.StopLossMonitor;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.KeyboardHandler;
import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.AccountProjection;
import com.bookmap.plugin.rong.miniviteapp.models.Models.Snapshot;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StopLossMonitorTest {
    static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    static JsonObject account(double net, double covered) {
        return json("{\"positions\":{\"OFF_WATCHLIST\":{\"netQuantity\":" + net + "}},\"exitPairs\":{\"OFF_WATCHLIST\":[{\"STOP\":{\"orderID\":\"s\",\"quantity\":" + covered + ",\"price\":10,\"isBuy\":" + (net < 0) + "}}]}}");
    }
    @Test void closesOnceStrictlyAfterTenSecondsForLongAndShortPartialCoverage() {
        for (double net : new double[]{100, -100}) {
            StopLossMonitor monitor = new StopLossMonitor(); JsonObject snapshot = account(net, 60);
            assertEquals(40, monitor.observe(snapshot, 0).warnings.get("OFF_WATCHLIST"));
            assertTrue(monitor.observe(snapshot, 10000).close.isEmpty());
            assertEquals(List.of("OFF_WATCHLIST"), monitor.observe(snapshot, 10001).close);
            assertTrue(monitor.observe(snapshot, 30000).close.isEmpty());
            monitor.observe(new JsonObject(), 31000);
            assertEquals(1, monitor.observe(snapshot, 32000).warnings.size());
        }
    }
    @Test void restoredProtectionFlatAndReversalResetTheTimer() {
        StopLossMonitor monitor = new StopLossMonitor();
        monitor.observe(account(100, 0), 0); monitor.observe(account(100, 100), 9000);
        monitor.observe(account(100, 0), 10000);
        assertTrue(monitor.observe(account(100, 0), 19000).close.isEmpty());
        monitor.observe(account(-100, 0), 20000);
        assertTrue(monitor.observe(account(-100, 0), 30000).close.isEmpty());
        monitor.observe(account(0, 0), 30001);
        assertEquals(1, monitor.observe(account(100, 0), 30002).warnings.size());
    }
    @Test void duplicateWrongSideAndInactiveStopsDoNotHideExposure() {
        JsonObject value = account(100, 30); JsonArray pairs = value.getAsJsonObject("exitPairs").getAsJsonArray("OFF_WATCHLIST");
        pairs.add(pairs.get(0).deepCopy());
        pairs.add(json("{\"STOP\":{\"orderID\":\"wrong\",\"quantity\":100,\"price\":10,\"isBuy\":true}}"));
        pairs.add(json("{\"STOP\":{\"orderID\":\"inactive\",\"quantity\":100,\"price\":10,\"isBuy\":false,\"rawOrder\":{\"status\":\"AWAITING_PARENT_ORDER\"}}}"));
        assertEquals(70, new StopLossMonitor().observe(value, 0).warnings.get("OFF_WATCHLIST"));
    }
    @Test void safetyCloseMarketsAllPartialsAndUncoveredSharesWithoutAnEntryPlan() {
        for (double net : new double[]{100, -100}) {
            JsonObject input = json("{\"symbol\":\"A\",\"netQuantity\":" + net + ",\"entries\":[],\"pairs\":[]}");
            JsonArray pairs = input.getAsJsonArray("pairs");
            pairs.add(json("{\"STOP\":{\"orderID\":\"s\",\"quantity\":60,\"price\":10,\"isBuy\":" + (net < 0) + "}}"));
            pairs.add(json("{\"LIMIT\":{\"orderID\":\"l\",\"quantity\":20,\"price\":12,\"isBuy\":" + (net < 0) + "}}"));
            var plan = KeyboardHandler.handleKeyPressed(new Snapshot(input), "SafetyClosePosition", false, 0);
            assertEquals("stop_loss_safety_close", plan.action); assertTrue(plan.clearPending);
            assertEquals(3, plan.requests.size());
            double total = 0;
            for (var request : plan.requests) {
                assertEquals("MARKET", request.body.get("orderType").getAsString());
                JsonObject leg = request.body.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject();
                assertEquals(net > 0 ? "SELL" : "BUY_TO_COVER", leg.get("instruction").getAsString());
                total += leg.get("quantity").getAsDouble();
            }
            assertEquals(100, total);
            assertEquals("POST", plan.requests.get(2).method);
        }
    }
    @Test void standaloneClosingStopCoversRemainingQuantity() {
        JsonObject order = json("{\"orderId\":1,\"status\":\"WORKING\",\"orderStrategyType\":\"SINGLE\",\"orderType\":\"STOP\",\"quantity\":100,\"filledQuantity\":25,\"stopPrice\":10,\"orderLegCollection\":[{\"instruction\":\"SELL\",\"positionEffect\":\"CLOSING\",\"instrument\":{\"symbol\":\"A\",\"assetType\":\"EQUITY\"}}]}");
        JsonArray orders = new JsonArray(); orders.add(order); JsonObject canceled = order.deepCopy(); canceled.addProperty("status", "CANCELED"); orders.add(canceled);
        JsonObject account = AccountProjection.projectAccount(json("{\"positions\":[{\"instrument\":{\"symbol\":\"A\"},\"longQuantity\":75}],\"currentBalances\":{\"liquidationValue\":25000}}"), orders, "2026-10-16", symbol -> 10);
        assertEquals(1, account.getAsJsonObject("exitPairs").getAsJsonArray("A").size());
        assertTrue(new StopLossMonitor().observe(account, 0).warnings.isEmpty());
    }
}
