package com.bookmap.plugin.rong;

import com.bookmap.plugin.rong.executions.*;
import com.bookmap.plugin.rong.pricelines.*;
import com.bookmap.plugin.rong.miniviteapp.runtime.PremarketOrderIndex;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.NativeViews;
import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.*;
import com.bookmap.plugin.rong.miniviteapp.ports.HttpPort;
import com.google.gson.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AfterHoursOrderRefreshTest {
    static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    @Test void afterUtcMidnightFillAndCancellationReachExistingChartManagers() throws Exception {
        for (boolean history : new boolean[]{false, true}) for (String terminal : List.of("FILLED", "CANCELED")) {
            long entered = Instant.parse("2026-11-24T00:49:05Z").toEpochMilli();
            AtomicReference<String> status = new AtomicReference<>("WORKING");
            HttpPort http = (uri, method, headers, body) -> {
                Map<String, String> query = new HashMap<>();
                for (String item : uri.getRawQuery().split("&")) { String[] pair = item.split("=", 2); query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8)); }
                assertEquals("2026-11-23T05:00:00.000Z", query.get("fromEnteredTime"));
                assertEquals("2026-11-24T05:00:00.000Z", query.get("toEnteredTime"));
                JsonArray orders = new JsonArray();
                if (entered >= Instant.parse(query.get("fromEnteredTime")).toEpochMilli() && entered <= Instant.parse(query.get("toEnteredTime")).toEpochMilli()) orders.add(order(status.get()));
                return new HttpPort.Response(200, orders.toString());
            };
            ReadApi reads = new ReadApi(http); PremarketOrderIndex index = new PremarketOrderIndex(); index.acceptLocal(order("WORKING"));
            PriceLineStore lines = new PriceLineStore(); FilledExecutionStore fills = new FilledExecutionStore();
            PendingEntryOrderManager pending = new PendingEntryOrderManager(lines); pending.onInstrumentInitialized("NVDA", .01);
            FilledExecutionManager executed = new FilledExecutionManager(fills); executed.onInstrumentInitialized("NVDA", .01);
            SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
            try {
                server.registerAccountStateListener(pending); server.registerAccountStateListener(executed);
                for (String next : List.of("WORKING", terminal)) {
                    status.set(next);
                    JsonArray orders = history ? reads.getOrderHistory("test-account", "test-token", "2026-11-23") : reads.getOrders("test-account", "test-token", "2026-11-23");
                    index.observe(orders);
                    JsonObject account = AccountProjection.projectAccount(json("{\"currentBalances\":{\"liquidationValue\":25000}}"), orders, "2026-11-23", symbol -> 166);
                    JsonObject view = json("{\"type\":\"account_ready\",\"symbol\":\"NVDA\"}");
                    view.addProperty("timestamp", entered + 2000); view.add("account", account); view.add("premarketOrders", index.snapshot("NVDA"));
                    NativeViews.project(view).forEach(server::acceptLocalMessage);
                    assertEquals(next.equals("WORKING") ? 1 : 0, lines.getLines("NVDA").size(), "Confirmed terminal orders must remove the pending chart line");
                }
                assertEquals(terminal.equals("FILLED") ? 1 : 0, fills.getMarkers("NVDA").size());
                if (terminal.equals("FILLED")) {
                    FilledExecutionMarker fill = fills.getMarkers("NVDA").get(0);
                    assertEquals(166.21, fill.getRealPrice()); assertEquals(5, fill.getQuantity());
                    assertEquals(Instant.parse("2026-11-24T00:49:06Z").toEpochMilli() * 1000000, fill.getTimeNs());
                }
            } finally { server.shutdown(); }
        }
    }
    static JsonObject order(String status) {
        JsonObject order = json("{\"orderId\":123,\"session\":\"SEAMLESS\",\"orderType\":\"LIMIT\",\"orderStrategyType\":\"SINGLE\",\"quantity\":5,\"price\":165.91,\"enteredTime\":\"2026-11-24T00:49:05Z\",\"orderLegCollection\":[{\"legId\":1,\"orderLegType\":\"EQUITY\",\"instruction\":\"BUY\",\"positionEffect\":\"OPENING\",\"quantity\":5,\"instrument\":{\"symbol\":\"NVDA\",\"assetType\":\"EQUITY\"}}]}");
        order.addProperty("status", status); order.addProperty("filledQuantity", status.equals("FILLED") ? 5 : 0);
        if (status.equals("FILLED")) order.add("orderActivityCollection", JsonParser.parseString("[{\"activityType\":\"EXECUTION\",\"executionType\":\"FILL\",\"executionLegs\":[{\"legId\":1,\"time\":\"2026-11-24T00:49:06+0000\",\"quantity\":5,\"price\":166.21}]}]"));
        return order;
    }
}
