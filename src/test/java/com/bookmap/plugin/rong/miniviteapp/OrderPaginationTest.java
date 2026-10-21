package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.account.ExecutionExports;
import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.AccountProjection;
import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.ReadApi;
import com.bookmap.plugin.rong.miniviteapp.ports.HttpPort;
import com.google.gson.*;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderPaginationTest {
    private static final String DATE = "2026-10-01";
    private static final long BURST = Instant.parse(DATE + "T13:30:00Z").toEpochMilli();

    @Test void csvContainsEveryFillAcrossCappedPagesInBothModes() throws Exception {
        JsonArray source = new JsonArray();
        // Multiple pages inside one second and an order at the split boundary.
        for (int id = 0; id < 1201; id++) source.add(filledOrder(id, BURST + id));
        source.add(filledOrder(1201, Instant.parse("2026-09-30T13:30:00Z").toEpochMilli()));
        for (boolean useTimeWindows : new boolean[]{false, true}) {
            AtomicInteger requests = new AtomicInteger(), capped = new AtomicInteger();
            HttpPort http = (uri, method, headers, body) -> {
                requests.incrementAndGet();
                Map<String, String> query = new HashMap<>();
                for (String item : uri.getRawQuery().split("&")) {
                    String[] pair = item.split("=", 2);
                    query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                }
                assertEquals("500", query.get("maxResults"));
                long from = Instant.parse(query.get("fromEnteredTime")).toEpochMilli();
                long to = Instant.parse(query.get("toEnteredTime")).toEpochMilli();
                List<JsonElement> matching = new ArrayList<>();
                for (JsonElement value : source) {
                    long entered = value.getAsJsonObject().get("enteredTime").getAsLong();
                    if (entered >= from && entered <= to) matching.add(value);
                }
                if (matching.size() >= 500) capped.incrementAndGet();
                JsonArray page = new JsonArray();
                matching.subList(Math.max(0, matching.size() - 500), matching.size()).forEach(page::add);
                return new HttpPort.Response(200, page.toString());
            };
            JsonArray orders = new ReadApi(http).getOrders("account", "token", DATE, useTimeWindows, 1);
            assertTrue(requests.get() > 3 && capped.get() > 1);
            assertEquals(source.size(), orders.size());
            Set<Integer> ids = new HashSet<>();
            orders.forEach(order -> ids.add(order.getAsJsonObject().get("orderId").getAsInt()));
            assertEquals(source.size(), ids.size());
            for (int id = 0; id < source.size(); id++) assertTrue(ids.contains(id));
            JsonObject account = JsonParser.parseString("{\"currentBalances\":{\"liquidationValue\":25000}}").getAsJsonObject();
            JsonObject projected = AccountProjection.projectAccount(account, orders, DATE, symbol -> 10);
            JsonArray fills = projected.getAsJsonObject("executions").getAsJsonArray("AAPL");
            assertEquals(source.size(), fills.size());
            String csv = ExecutionExports.executionTradesCsv(fills, BURST, ZoneId.of("UTC"));
            assertEquals(source.size(), csv.lines().filter(line -> line.contains(",STOCK,BUY,")).count());
        }
    }

    @Test void laterPageFailureRejectsTheWholeRead() {
        AtomicInteger requests = new AtomicInteger();
        HttpPort http = (uri, method, headers, body) -> {
            int request = requests.incrementAndGet();
            return new HttpPort.Response(request == 3 ? 503 : 200,
                    request == 1 ? cappedPage() : "[{\"orderId\":1}]");
        };
        IOException error = assertThrows(IOException.class, () -> new ReadApi(http).getOrders("account", "token", DATE, false));
        assertEquals("Schwab read HTTP 503", error.getMessage());
        assertEquals(3, requests.get());
    }

    @Test void cappedBracketResponseBelow500RootOrdersIsSubdivided() throws Exception {
        List<JsonObject> roots = new ArrayList<>();
        for (int id = 0; id < 61; id++) {
            JsonObject root = new JsonObject(); root.addProperty("orderId", id); root.addProperty("enteredTime", BURST + id);
            JsonArray children = new JsonArray();
            for (int child = 0; child < 30; child++) {
                JsonObject order = new JsonObject(); order.addProperty("orderId", 1000 + id * 30 + child); children.add(order);
            }
            root.add("childOrderStrategies", children); roots.add(root);
        }
        AtomicInteger requests = new AtomicInteger();
        HttpPort http = (uri, method, headers, body) -> {
            requests.incrementAndGet(); Map<String, String> query = new HashMap<>();
            for (String item : uri.getRawQuery().split("&")) {
                String[] pair = item.split("=", 2); query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            long from = Instant.parse(query.get("fromEnteredTime")).toEpochMilli();
            long to = Instant.parse(query.get("toEnteredTime")).toEpochMilli();
            List<JsonObject> matching = new ArrayList<>();
            for (JsonObject root : roots) {
                long entered = root.get("enteredTime").getAsLong();
                if (entered >= from && entered <= to) matching.add(root);
            }
            JsonArray page = new JsonArray(); matching.subList(Math.max(0, matching.size() - 20), matching.size()).forEach(page::add);
            return new HttpPort.Response(200, page.toString());
        };
        JsonArray orders = new ReadApi(http).getOrders("account", "token", DATE, false);
        assertTrue(requests.get() > 1); assertEquals(roots.size(), orders.size());
        Set<Integer> ids = new HashSet<>();
        orders.forEach(order -> ids.add(order.getAsJsonObject().get("orderId").getAsInt()));
        for (int id = 0; id < roots.size(); id++) assertTrue(ids.contains(id));
    }

    @Test void unsplittableFullPageFailsExplicitly() {
        AtomicInteger requests = new AtomicInteger(); String page = cappedPage();
        HttpPort http = (uri, method, headers, body) -> { requests.incrementAndGet(); return new HttpPort.Response(200, page); };
        IOException error = assertThrows(IOException.class, () -> new ReadApi(http).getOrders("account", "token", DATE, false));
        assertTrue(error.getMessage().contains("one-millisecond window; read may be incomplete"));
        assertTrue(requests.get() < 30);
    }

    @Test void olderWorkingTreesOutsideRangeDoNotForceSubdivision() throws Exception {
        JsonObject old = new JsonObject(); old.addProperty("orderId", 1);
        old.addProperty("enteredTime", "2024-05-12T01:23:55+0000");
        JsonArray children = new JsonArray();
        for (int child = 0; child < 600; child++) {
            JsonObject order = new JsonObject(); order.addProperty("orderId", 1000 + child); children.add(order);
        }
        old.add("childOrderStrategies", children);
        JsonObject current = new JsonObject(); current.addProperty("orderId", 2);
        current.addProperty("enteredTime", DATE + "T13:30:00+0000");
        JsonArray page = new JsonArray(); page.add(old); page.add(current);
        AtomicInteger requests = new AtomicInteger();
        HttpPort http = (uri, method, headers, body) -> { requests.incrementAndGet(); return new HttpPort.Response(200, page.toString()); };
        JsonArray orders = new ReadApi(http).getOrders("account", "token", DATE, false);
        assertEquals(1, requests.get()); assertEquals(2, orders.size());
    }

    private static String cappedPage() {
        JsonArray page = new JsonArray();
        for (int id = 0; id < 500; id++) { JsonObject order = new JsonObject(); order.addProperty("orderId", id); page.add(order); }
        return page.toString();
    }

    private static JsonObject filledOrder(int id, long entered) {
        JsonObject order = JsonParser.parseString("{\"orderStrategyType\":\"SINGLE\",\"status\":\"FILLED\","
                + "\"orderLegCollection\":[{\"legId\":1,\"instruction\":\"BUY\",\"positionEffect\":\"OPENING\","
                + "\"instrument\":{\"symbol\":\"AAPL\",\"assetType\":\"EQUITY\"}}],"
                + "\"orderActivityCollection\":[{\"activityType\":\"EXECUTION\",\"executionType\":\"FILL\","
                + "\"executionLegs\":[{\"legId\":1,\"quantity\":1,\"price\":10}]}]}").getAsJsonObject();
        order.addProperty("orderId", id); order.addProperty("enteredTime", entered);
        order.getAsJsonArray("orderActivityCollection").get(0).getAsJsonObject().getAsJsonArray("executionLegs")
                .get(0).getAsJsonObject().addProperty("time", Instant.ofEpochMilli(BURST + id).toString());
        return order;
    }
}
