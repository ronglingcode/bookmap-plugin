package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.libraries.firestore.DocumentCodec;
import com.bookmap.plugin.rong.miniviteapp.ports.*;
import com.bookmap.plugin.rong.miniviteapp.runtime.TradingRuntime;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TwoStockTradingTest {
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }

    @Test void secondStockCanEnterWhileFirstIsHeldAndBothSurviveConfigRefresh() throws Exception {
        JsonArray fixtures = JsonParser.parseReader(new InputStreamReader(getClass().getResourceAsStream("/state-fixtures.json"), StandardCharsets.UTF_8)).getAsJsonArray();
        JsonObject config = null, saved = null;
        for (JsonElement item : fixtures) {
            JsonObject fixture = item.getAsJsonObject();
            if (fixture.get("name").getAsString().equals("two selected stocks config")) config = fixture.getAsJsonArray("args").get(0).getAsJsonObject().deepCopy();
            if (fixture.get("name").getAsString().equals("new state and accepted entry")) saved = fixture.getAsJsonObject("result").deepCopy();
        }
        assertNotNull(config); assertNotNull(saved);
        final JsonObject configData = config, savedState = saved;
        JsonObject secondPlan = configData.getAsJsonArray("plans").get(0).getAsJsonObject().deepCopy();
        secondPlan.addProperty("symbol", "TSLA"); configData.getAsJsonArray("plans").set(1, secondPlan);
        AtomicLong now = new AtomicLong(Instant.parse("2026-10-01T13:32:00Z").toEpochMilli());
        JsonObject secrets = json("{\"access_token\":\"fake-token\",\"accountHashValue\":\"fake-account\",\"expires_at\":9999999999999}");
        JsonArray bothPositions = new JsonArray(); bothPositions.add(position("AAPL", 90));
        AtomicReference<String> positionData = new AtomicReference<>(bothPositions.toString());
        bothPositions.add(position("TSLA", 10));
        List<JsonObject> events = new CopyOnWriteArrayList<>(), orders = new CopyOnWriteArrayList<>();
        List<String> logs = new CopyOnWriteArrayList<>();
        HttpPort http = (uri, method, headers, body) -> {
            String path = uri.getPath();
            if (path.endsWith(":runQuery")) {
                JsonObject document = new JsonObject(); document.add("fields", DocumentCodec.encodeFields(configData));
                JsonObject row = new JsonObject(); row.add("document", document); JsonArray rows = new JsonArray(); rows.add(row);
                return new HttpPort.Response(200, rows.toString());
            }
            if (path.endsWith("/tradingState")) {
                JsonObject document = new JsonObject(); document.add("fields", DocumentCodec.encodeFields(savedState));
                return new HttpPort.Response(200, method.equals("PATCH") ? "{}" : document.toString());
            }
            if (path.endsWith("/accounts")) return new HttpPort.Response(200, "[{\"securitiesAccount\":{\"positions\":" + positionData.get() + ",\"currentBalances\":{\"liquidationValue\":25000}}}]");
            if (path.endsWith("/orders")) {
                if (method.equals("POST")) { orders.add(json(body)); positionData.set(bothPositions.toString()); return new HttpPort.Response(201, "", Map.of("Location", "https://fake.invalid/orders/123")); }
                return new HttpPort.Response(200, "[]");
            }
            if (path.endsWith("/userPreference")) return new HttpPort.Response(200, "{\"streamerInfo\":[{\"streamerSocketUrl\":\"wss://fake.invalid\",\"schwabClientCustomerId\":\"customer\",\"schwabClientCorrelId\":\"correl\",\"schwabClientChannel\":\"channel\",\"schwabClientFunctionId\":\"function\"}]}");
            if (path.contains("/reference/")) return new HttpPort.Response(200, "{\"results\":{\"weighted_shares_outstanding\":1000000000}}");
            if (path.contains("/v3/trades/")) return new HttpPort.Response(200, "{\"results\":[]}");
            if (path.contains("/aggs/")) return new HttpPort.Response(200, "{\"results\":[{\"t\":1790859600000,\"o\":10,\"h\":11,\"l\":9,\"c\":10,\"v\":1000000,\"vw\":10},{\"t\":1790861400000,\"o\":10,\"h\":11,\"l\":9,\"c\":10,\"v\":2000000,\"vw\":10}]}");
            throw new AssertionError("Unexpected fake route " + path);
        };
        CredentialPort credentials = new CredentialPort() {
            public JsonObject loadSchwab() { return secrets.deepCopy(); }
            public void saveSchwab(JsonObject value) { fail("Valid fake token must not refresh"); }
        };
        try (TradingRuntime runtime = new TradingRuntime(http, credentials,
                json("{\"firebaseConfig\":{\"projectId\":\"fake-project\"},\"massive\":{\"apiKey\":\"fake-massive\"}}"),
                (url, handlers) -> new SocketPort.Connection() { public void send(String value) { } public void close() { } },
                (delay, run) -> () -> {}, Runnable::run, now::get, new TradingRuntime.Events() {
                    public void message(JsonObject value) { events.add(value.deepCopy()); }
                    public void log(String symbol, String value) { logs.add(value); }
                    public void notify(String symbol, String value) { logs.add(value); }
                })) {
            runtime.start().get(2, TimeUnit.SECONDS);
            for (String symbol : List.of("AAPL", "TSLA")) assertTrue(runtime.view(symbol, "test").has("market"), symbol);
            assertTrue(runtime.dispatch(json("{\"symbol\":\"TSLA\",\"tradebook_id\":\"GapGiveAndGoBookmapReversal\",\"entry_method\":\"0.1 R\",\"use_market_order\":true,\"price\":10}")));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (events.stream().noneMatch(value -> value.has("outcome")) && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(events.stream().anyMatch(value -> value.has("outcome") && value.get("outcome").getAsString().equals("accepted")), logs.toString());
            assertEquals(1, orders.size());
            assertEquals("TSLA", orders.get(0).getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().getAsJsonObject("instrument").get("symbol").getAsString());
            runtime.pendingPersistence().get(2, TimeUnit.SECONDS);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (runtime.view("TSLA", "test").getAsJsonObject("account").getAsJsonObject("positions").size() < 2 && System.nanoTime() < deadline) Thread.sleep(5);
            JsonObject view = runtime.view("TSLA", "test"), state = view.getAsJsonObject("state").getAsJsonObject("stateBySymbol");
            assertEquals(2, view.getAsJsonObject("account").getAsJsonObject("positions").size());
            assertEquals(100, state.getAsJsonObject("AAPL").getAsJsonObject("breakoutTradeStateForLong").get("initialQuantity").getAsInt());
            assertTrue(state.getAsJsonObject("TSLA").getAsJsonObject("breakoutTradeStateForLong").get("hasValue").getAsBoolean());
            runtime.dispatch(json("{\"type\":\"manual_inputs\",\"symbol\":\"AAPL\",\"fixedQuantity\":20}"));
            runtime.dispatch(json("{\"type\":\"manual_inputs\",\"symbol\":\"TSLA\",\"fixedQuantity\":30}"));
            assertEquals(20, runtime.manualInputs("AAPL").get("fixedQuantity").getAsInt());
            assertEquals(30, runtime.manualInputs("TSLA").get("fixedQuantity").getAsInt());
            configData.getAsJsonArray("stockSelections").remove(1); runtime.refreshConfig();
            assertFalse(runtime.view("TSLA", "test").has("plan"));
            configData.getAsJsonArray("stockSelections").add("TSLA"); runtime.refreshConfig();
            assertTrue(runtime.view("TSLA", "test").has("plan"));
            configData.getAsJsonArray("stockSelections").add("MSFT"); runtime.refreshConfig();
            assertTrue(logs.stream().anyMatch(value -> value.contains("more than 2 stocks in watchlist: AAPL, TSLA, MSFT")));
            assertTrue(runtime.view("TSLA", "test").has("plan"));
            runtime.pendingPersistence().get(2, TimeUnit.SECONDS);
        }
    }

    private static JsonObject position(String symbol, int quantity) {
        JsonObject result = json("{\"instrument\":{\"assetType\":\"EQUITY\"},\"averagePrice\":10}");
        result.getAsJsonObject("instrument").addProperty("symbol", symbol); result.addProperty("longQuantity", quantity); return result;
    }
}
