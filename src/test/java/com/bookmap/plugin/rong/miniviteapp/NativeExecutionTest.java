package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.IndicatorConfig;
import com.bookmap.plugin.rong.miniviteapp.api.schwab.Api;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeExecutionTest {
    private static final String KEY = "test-pairing-key-at-least-32-characters";
    private static final String ORIGIN = "http://localhost:5173";
    private static JsonObject json(String contents) { return JsonParser.parseString(contents).getAsJsonObject(); }
    private static JsonObject action(String key) {
        JsonObject json = new JsonObject(); json.addProperty("keyCode", key); json.addProperty("symbol", "AAPL"); return json;
    }
    private static JsonObject fixture(String name) {
        var reader = new java.io.InputStreamReader(NativeExecutionTest.class.getResourceAsStream("/direct-execution-fixtures.json"), StandardCharsets.UTF_8);
        for (var value : JsonParser.parseReader(reader).getAsJsonArray()) {
            var fixture = value.getAsJsonObject();
            if (fixture.get("name").getAsString().equals(name)) return fixture.getAsJsonObject("state").deepCopy();
        }
        throw new AssertionError(name);
    }
    private static final class Rig implements AutoCloseable {
        final Object owner = new Object();
        final List<JsonObject> events = new CopyOnWriteArrayList<>();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<JsonObject> bodies = new CopyOnWriteArrayList<>();
        final AtomicInteger mutations = new AtomicInteger();
        final HttpServer server;
        final MiniViteApp engine;
        JsonObject state = fixture("cancel all entries below threshold");
        volatile int mutationStatus = 204;
        volatile boolean orderFilled;
        final CountDownLatch mutationEntered = new CountDownLatch(1);
        volatile CountDownLatch allowMutation;
        String epoch;
        Rig() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/accounts/hash", exchange -> {
                try {
                    assertEquals("Bearer fake-access-token", exchange.getRequestHeaders().getFirst("Authorization"));
                    String method = exchange.getRequestMethod(), path = exchange.getRequestURI().getPath();
                    requests.add(method + " " + path);
                    if (method.equals("GET")) {
                        JsonObject response;
                        if (path.equals("/accounts/hash")) {
                            double quantity = state.get("netQuantity").getAsDouble();
                            response = json("{\"securitiesAccount\":{\"positions\":[{\"instrument\":{\"symbol\":\"AAPL\"},\"longQuantity\":0,\"shortQuantity\":0}]}}");
                            var position = response.getAsJsonObject("securitiesAccount").getAsJsonArray("positions").get(0).getAsJsonObject();
                            position.addProperty("longQuantity", Math.max(0, quantity)); position.addProperty("shortQuantity", Math.max(0, -quantity));
                        } else {
                            String id = path.substring(path.lastIndexOf('/') + 1);
                            JsonObject expected = findOrder(id);
                            response = new JsonObject();
                            response.addProperty("orderType", expected.get("orderType").getAsString());
                            response.addProperty("status", orderFilled ? "FILLED" : "WORKING");
                            response.addProperty("quantity", expected.get("quantity").getAsDouble());
                            response.addProperty("filledQuantity", orderFilled ? 1 : 0);
                            response.add(expected.get("orderType").getAsString().equals("STOP") ? "stopPrice" : "price", expected.get("price"));
                            boolean entry = id.equals("101") || id.equals("102");
                            boolean buy = expected.get("isBuy").getAsBoolean();
                            var leg = json("{\"instrument\":{\"assetType\":\"EQUITY\",\"symbol\":\"AAPL\"}}");
                            leg.addProperty("instruction", entry ? buy ? "BUY" : "SELL_SHORT" : buy ? "BUY_TO_COVER" : "SELL");
                            JsonArray legs = new JsonArray(); legs.add(leg); response.add("orderLegCollection", legs);
                        }
                        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                    } else {
                        mutations.incrementAndGet(); mutationEntered.countDown();
                        if (allowMutation != null) allowMutation.await(3, TimeUnit.SECONDS);
                        if (!method.equals("DELETE")) bodies.add(json(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                        if (!method.equals("DELETE")) exchange.getResponseHeaders().add("Location", "/accounts/hash/orders/999");
                        exchange.sendResponseHeaders(mutationStatus, -1); // Success with no JSON body.
                    }
                } catch (Exception error) { throw new RuntimeException(error); }
                finally { exchange.close(); }
            });
            server.start();
            engine = new MiniViteApp(new Api(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/")), KEY,
                    (connection, event) -> events.add(event.deepCopy()));
        }
        JsonObject findOrder(String id) {
            for (var value : state.getAsJsonArray("entries")) if (value.getAsJsonObject().get("orderID").getAsString().equals(id)) return value.getAsJsonObject();
            for (var value : state.getAsJsonArray("pairs")) for (String key : new String[]{"LIMIT", "STOP"}) {
                var pair = value.getAsJsonObject();
                if (pair.has(key) && pair.getAsJsonObject(key).get("orderID").getAsString().equals(id)) return pair.getAsJsonObject(key);
            }
            throw new AssertionError("unexpected order " + id);
        }
        void connect() {
            engine.setEnabled(true, true); engine.setLive("AAPL", true);
            engine.receive(owner, ORIGIN, json("{\"type\":\"execution_hello\",\"version\":1,\"live\":true,\"broker\":\"Schwab\",\"pairingKey\":\"" + KEY + "\"}"));
            epoch = events.get(events.size()-1).get("epoch").getAsString();
            token(1, System.currentTimeMillis() + 120_000);
            updateState(System.currentTimeMillis()-1);
        }
        JsonObject message(String type) {
            var json = new JsonObject(); json.addProperty("type", type); json.addProperty("epoch", epoch); return json;
        }
        void token(int generation, long expiry) {
            var json = message("execution_token"); json.addProperty("generation", generation);
            json.addProperty("accessToken", "fake-access-token"); json.addProperty("accountHash", "hash"); json.addProperty("expiresAt", expiry);
            engine.receive(owner, ORIGIN, json);
        }
        void updateState(long observedAt) {
            state.addProperty("revision", System.nanoTime()); state.addProperty("observedAt", observedAt);
            state.addProperty("quoteObservedAt", System.currentTimeMillis());
            var json = message("execution_state"); json.addProperty("accountHash", "hash");
            var symbols = new JsonArray(); symbols.add(state.deepCopy()); json.add("symbols", symbols); engine.receive(owner, ORIGIN, json);
        }
        JsonObject finish() throws Exception {
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < limit) {
                for (var event : events) if (event.get("type").getAsString().equals("execution_result")) return event;
                Thread.sleep(10);
            }
            throw new AssertionError("native result timeout");
        }
        @Override public void close() { engine.close(); server.stop(0); }
    }
    @Test void flagsOffPreserveLegacyAndEntriesNeverRouteNatively() throws Exception {
        var config = new IndicatorConfig();
        assertFalse(config.isEnabled(IndicatorConfig.EXPERIMENTAL_DIRECT_BROKER_EXECUTION));
        assertFalse(config.isEnabled(IndicatorConfig.EXPERIMENTAL_DIRECT_EXIT_EXECUTION));
        try (var rig = new Rig()) {
            assertFalse(rig.engine.route(action("KeyC"))); rig.connect();
            for (var key : new String[]{"KeyB", "KeyS", "KeyA", "KeyW"}) assertFalse(rig.engine.route(action(key)));
            rig.engine.setEnabled(true, false); assertFalse(rig.engine.route(action("KeyM")));
            assertEquals(0, rig.mutations.get());
        }
    }
    @Test void directCancelHandlesEmptySuccessAndDoesNotRepeatWhileAwaitingReconciliation() throws Exception {
        try (var rig = new Rig()) {
            rig.connect(); rig.allowMutation = new CountDownLatch(1);
            assertTrue(rig.engine.route(action("KeyC")));
            assertTrue(rig.mutationEntered.await(3, TimeUnit.SECONDS));
            assertTrue(rig.engine.route(action("KeyC"))); rig.allowMutation.countDown();
            assertEquals("accepted", rig.finish().get("outcome").getAsString());
            assertEquals(2, rig.mutations.get());
            assertTrue(rig.engine.route(action("KeyC"))); assertEquals(2, rig.mutations.get());
            rig.state.add("entries", new JsonArray()); rig.updateState(System.currentTimeMillis()+10);
            assertFalse(rig.engine.status().get("blocked").getAsBoolean());
            assertTrue(rig.events.stream().noneMatch(event -> event.toString().contains(KEY) || event.toString().contains("fake-access-token")));
        }
    }
    @Test void nativeExitUsesClosingPutAndRefusesConcurrentBrowserMutation() throws Exception {
        try (var rig = new Rig()) {
            rig.state = fixture("market out smallest first tie"); rig.connect();
            rig.allowMutation = new CountDownLatch(1);
            assertTrue(rig.engine.route(action("KeyM"))); assertTrue(rig.mutationEntered.await(3, TimeUnit.SECONDS));
            var legacy = rig.message("execution_legacy_begin"); legacy.addProperty("requestId", "browser"); rig.engine.receive(rig.owner, ORIGIN, legacy);
            assertFalse(rig.events.get(rig.events.size()-1).get("allowed").getAsBoolean());
            rig.allowMutation.countDown(); assertEquals("accepted", rig.finish().get("outcome").getAsString());
            assertEquals(1, rig.mutations.get());
            assertTrue(rig.requests.contains("PUT /accounts/hash/orders/202"));
            assertEquals("SELL", rig.bodies.get(0).getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("instruction").getAsString());
        }
    }
    @Test void expiryStalenessReplayAndPartialFillsNeverSendMutations() throws Exception {
        try (var rig = new Rig()) {
            rig.connect(); rig.token(2, System.currentTimeMillis()+1000); rig.engine.route(action("KeyC")); assertEquals(0, rig.requests.size());
            rig.token(3, System.currentTimeMillis()+120_000); rig.updateState(System.currentTimeMillis()-20_000);
            rig.engine.route(action("KeyC")); assertEquals(0, rig.requests.size());
            rig.updateState(System.currentTimeMillis()); rig.engine.setLive("AAPL", false);
            rig.engine.route(action("KeyC")); assertEquals(0, rig.requests.size());
            rig.engine.setLive("AAPL", true); rig.orderFilled = true; rig.engine.route(action("KeyC"));
            assertEquals("rejected", rig.finish().get("outcome").getAsString()); assertEquals(0, rig.mutations.get());
        }
    }
    @Test void ambiguousBrokerFailureHoldsExecutionUntilExplicitReview() throws Exception {
        try (var rig = new Rig()) {
            rig.connect(); rig.mutationStatus = 503; rig.engine.route(action("KeyC"));
            assertEquals("unknown", rig.finish().get("outcome").getAsString());
            rig.updateState(System.currentTimeMillis()+10); rig.engine.route(action("KeyC"));
            assertEquals(1, rig.mutations.get()); assertTrue(rig.engine.status().get("requiresReview").getAsBoolean());
            rig.engine.setEnabled(false, false); assertTrue(rig.engine.route(action("KeyC")));
            assertTrue(rig.engine.resetAfterBrokerReview()); assertFalse(rig.engine.route(action("KeyC")));
        }
    }
    @Test void legacyFenceInvalidatesSnapshotsAndUnknownLegacyOutcomeRequiresReview() throws Exception {
        try (var rig = new Rig()) {
            rig.connect(); var begin = rig.message("execution_legacy_begin"); begin.addProperty("requestId", "legacy");
            rig.engine.receive(rig.owner, ORIGIN, begin); assertTrue(rig.events.get(rig.events.size()-1).get("allowed").getAsBoolean());
            rig.engine.route(action("KeyC")); assertEquals(0, rig.requests.size());
            var end = rig.message("execution_legacy_end"); end.addProperty("requestId", "legacy"); end.addProperty("outcome", "unknown");
            rig.engine.receive(rig.owner, ORIGIN, end); rig.updateState(System.currentTimeMillis()+10);
            assertTrue(rig.engine.status().get("requiresReview").getAsBoolean()); rig.engine.route(action("KeyC")); assertEquals(0, rig.requests.size());
        }
    }
    @Test void unpairedConnectionsCannotUpdateCredentialsAndSecondTabCannotOwnSession() throws Exception {
        try (var rig = new Rig()) {
            rig.engine.setEnabled(true, true);
            var hello = json("{\"type\":\"execution_hello\",\"version\":1,\"live\":true,\"broker\":\"Schwab\",\"pairingKey\":\""+KEY+"\"}");
            rig.engine.receive(rig.owner, "https://untrusted.example", hello);
            assertEquals("execution_rejected", rig.events.get(0).get("type").getAsString());
            rig.connect(); Object secondTab = new Object(); rig.engine.receive(secondTab, ORIGIN, hello);
            assertEquals("execution_rejected", rig.events.get(rig.events.size()-1).get("type").getAsString());
            rig.token(0, 1); // Older credential generations cannot overwrite a valid token.
            rig.engine.route(action("KeyC")); assertEquals("accepted", rig.finish().get("outcome").getAsString());
        }
    }
}
