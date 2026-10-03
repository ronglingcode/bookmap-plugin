package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.bookmap.plugin.rong.executions.FilledExecutionManager;
import com.bookmap.plugin.rong.executions.FilledExecutionMarker;
import com.bookmap.plugin.rong.executions.FilledExecutionStore;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.NativeViews;
import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.AccountProjection;
import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.ReadApi;
import com.bookmap.plugin.rong.miniviteapp.ports.HttpPort;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class FilledExecutionPipelineTest {

    @Test
    void wideOrderReadBisectsWhenSchwabReturnsTheResultLimit() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        String capped = "[" + "{\"orderId\":1},".repeat(499) + "{\"orderId\":1}]";
        HttpPort http = (uri, method, headers, body) -> {
            int request = requests.incrementAndGet();
            return new HttpPort.Response(200, request == 1 ? capped
                    : "[{\"orderId\":" + request + "}]");
        };

        JsonArray orders = new ReadApi(http).getOrders("account", "token", "2026-10-02", false, 59);

        assertEquals(3, requests.get());
        assertEquals(2, orders.size());
    }

    @Test
    void priorDayOrderFilledTodayReachesBookmapMarkerStore() throws Exception {
        String order = "{\"orderId\":42,\"orderStrategyType\":\"SINGLE\",\"status\":\"FILLED\","
                + "\"orderLegCollection\":[{\"legId\":1,\"orderLegType\":\"EQUITY\","
                + "\"instruction\":\"BUY\",\"positionEffect\":\"OPENING\","
                + "\"instrument\":{\"symbol\":\"NVDA\",\"assetType\":\"EQUITY\"}}],"
                + "\"orderActivityCollection\":[{\"activityType\":\"EXECUTION\","
                + "\"executionType\":\"FILL\",\"executionLegs\":[{\"legId\":1,"
                + "\"time\":\"2026-10-02T13:35:00+0000\",\"quantity\":10,\"price\":237.12}]}]}";
        AtomicReference<String> fromEnteredTime = new AtomicReference<>();
        HttpPort http = (uri, method, headers, body) -> {
            for (String parameter : uri.getRawQuery().split("&")) {
                String[] pair = parameter.split("=", 2);
                if (pair[0].equals("fromEnteredTime")) {
                    fromEnteredTime.set(URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                }
            }
            return new HttpPort.Response(200, "[" + order + "]");
        };
        JsonArray orders = new ReadApi(http).getOrders("account", "token", "2026-10-02", false, 59);
        assertEquals("2026-08-04T00:00:00.000Z", fromEnteredTime.get());

        JsonObject account = JsonParser.parseString("{\"currentBalances\":{\"liquidationValue\":25000}}").getAsJsonObject();
        JsonObject projected = AccountProjection.projectAccount(account, orders, "2026-10-02", symbol -> 237.12);
        JsonObject view = new JsonObject();
        view.addProperty("symbol", "NVDA");
        view.addProperty("type", "account_ready");
        view.addProperty("timestamp", Instant.parse("2026-10-02T13:36:00Z").toEpochMilli());
        view.add("account", projected);

        FilledExecutionStore store = new FilledExecutionStore();
        FilledExecutionManager manager = new FilledExecutionManager(store);
        SignalWebSocketServer server = new SignalWebSocketServer(0, 0.5);
        server.registerAccountStateListener(manager);
        manager.onInstrumentInitialized("NVDA:NASDAQ:STOCKS@BMD", 0.01);
        NativeViews.project(view).forEach(server::acceptLocalMessage);

        assertEquals(1, store.getMarkers("NVDA").size());
        FilledExecutionMarker marker = store.getMarkers("NVDA").get(0);
        assertEquals(237.12, marker.getRealPrice(), 0.00001);
        assertEquals(23712, marker.getPriceInTicks(), 0.00001);
        assertEquals(Instant.parse("2026-10-02T13:35:00Z").toEpochMilli() * 1_000_000L,
                marker.getTimeNs());
        assertTrue(marker.isBuy());
        manager.shutdown();
    }
}
