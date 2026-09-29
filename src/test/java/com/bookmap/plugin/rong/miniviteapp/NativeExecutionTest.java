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
        final List<String> authorizations = new CopyOnWriteArrayList<>();
        final List<JsonObject> bodies = new CopyOnWriteArrayList<>();
        final AtomicInteger mutations = new AtomicInteger();
        final HttpServer server;
        final MiniViteApp engine;
        JsonObject state = fixture("cancel all entries below threshold");
        volatile int mutationStatus = 204;
        volatile boolean orderFilled;
        volatile boolean omitLocation, pendingSymbolOrder;
        volatile String pendingStatus = "QUEUED";
        volatile double brokerQuantity = Double.NaN, buyingPower = 1_000_000;
        final CountDownLatch mutationEntered = new CountDownLatch(1);
        volatile CountDownLatch allowMutation;
        String epoch;
        Rig() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/accounts/hash", exchange -> {
                try {
                    authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    String method = exchange.getRequestMethod(), path = exchange.getRequestURI().getPath();
                    requests.add(method + " " + path);
                    if (method.equals("GET")) {
                        JsonObject response;
                        if (path.equals("/accounts/hash")) {
                            double quantity = Double.isFinite(brokerQuantity) ? brokerQuantity : state.get("netQuantity").getAsDouble();
                            response = json("{\"securitiesAccount\":{\"positions\":[{\"instrument\":{\"symbol\":\"AAPL\"},\"longQuantity\":0,\"shortQuantity\":0}]}}");
                            var position = response.getAsJsonObject("securitiesAccount").getAsJsonArray("positions").get(0).getAsJsonObject();
                            position.addProperty("longQuantity", Math.max(0, quantity)); position.addProperty("shortQuantity", Math.max(0, -quantity));
                            var balances = new JsonObject(); balances.addProperty("buyingPower", buyingPower);
                            response.getAsJsonObject("securitiesAccount").add("currentBalances", balances);
                        } else if (path.equals("/accounts/hash/orders")) {
                            String body = pendingSymbolOrder ? "[{\"status\":\"" + pendingStatus + "\",\"orderLegCollection\":[{\"instrument\":{\"symbol\":\"AAPL\"}}]}]" : "[]";
                            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                            return;
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
                        if (!method.equals("DELETE") && !omitLocation) exchange.getResponseHeaders().add("Location", "/accounts/hash/orders/999");
                        exchange.sendResponseHeaders(mutationStatus, -1); // Success with no JSON body.
                    }
                } catch (Exception error) { throw new RuntimeException(error); }
                finally { exchange.close(); }
            });
            server.start();
            engine = new MiniViteApp(new Api(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/")),
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
            engine.setEnabled(true, true);
            engine.receive(owner, ORIGIN, json("{\"type\":\"execution_hello\",\"version\":2,\"live\":true,\"broker\":\"Schwab\"}"));
            epoch = events.get(events.size()-1).get("epoch").getAsString();
            token(1, System.currentTimeMillis() + 120_000);
            updateState(System.currentTimeMillis()-1);
        }
        JsonObject connectEntry() {
            var stream = NativeExecutionTest.class.getResourceAsStream("/direct-entry-fixtures.json");
            var fixture = JsonParser.parseReader(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonArray().get(0).getAsJsonObject();
            state = fixture.getAsJsonObject("state").deepCopy();
            state.getAsJsonObject("entryContext").addProperty("observedAt", System.currentTimeMillis());
            connect(); engine.setEnabled(true, true, true);
            return fixture.getAsJsonObject("action").deepCopy();
        }
        JsonObject message(String type) {
            var json = new JsonObject(); json.addProperty("type", type); json.addProperty("epoch", epoch); return json;
        }
        void token(int generation, long expiry) {
            token(generation, expiry, "fake-access-token");
        }
        void token(int generation, long expiry, String accessToken) {
            var json = message("execution_token"); json.addProperty("generation", generation);
            json.addProperty("accessToken", accessToken); json.addProperty("accountHash", "hash"); json.addProperty("expiresAt", expiry);
            engine.receive(owner, ORIGIN, json);
        }
        void updateState(long observedAt) {
            updateState(observedAt, System.currentTimeMillis());
        }
        void updateState(long observedAt, long quoteObservedAt) {
            state.addProperty("revision", System.nanoTime()); state.addProperty("observedAt", observedAt);
            state.addProperty("quoteObservedAt", quoteObservedAt);
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
        assertFalse(config.isEnabled(IndicatorConfig.EXPERIMENTAL_DIRECT_ENTRY_EXECUTION));
        try (var rig = new Rig()) {
            assertFalse(rig.engine.route(action("KeyC"))); rig.connect();
            for (var key : new String[]{"KeyB", "KeyS", "KeyA", "KeyW"}) assertFalse(rig.engine.route(action(key)));
            rig.engine.setEnabled(true, false); assertFalse(rig.engine.route(action("KeyM")));
            assertEquals(0, rig.mutations.get());
        }
    }
    @Test void initialEntryPostsOneProtectedBracketAndWaitsForNewOrderObservation() throws Exception {
        try (var rig = new Rig()) {
            var entry = rig.connectEntry(); rig.engine.setEnabled(true, true, false);
            assertFalse(rig.engine.route(entry)); assertEquals(0, rig.mutations.get());
            rig.engine.setEnabled(true, true, true);
            assertTrue(rig.engine.route(entry));
            var result = rig.finish(); assertEquals("accepted", result.get("outcome").getAsString());
            assertEquals("wall_reversal_entry", result.get("action").getAsString());
            assertEquals(1, rig.mutations.get());
            assertEquals("TRIGGER", rig.bodies.get(0).get("orderStrategyType").getAsString());
            assertEquals(10, rig.bodies.get(0).getAsJsonArray("childOrderStrategies").size());
            assertEquals("RangeBoundBidReversal", result.getAsJsonObject("entry").getAsJsonObject("submitEntryResult").get("tradeBookID").getAsString());
            rig.updateState(System.currentTimeMillis()+1);
            assertTrue(rig.engine.status().get("blocked").getAsBoolean());
            assertTrue(rig.engine.route(entry)); assertEquals(1, rig.mutations.get());
            rig.state.add("observedOrderIds", JsonParser.parseString("[\"999\"]"));
            rig.state.getAsJsonArray("entries").add(json("{\"orderID\":\"999\",\"orderType\":\"STOP\",\"quantity\":1960,\"price\":10.01,\"isBuy\":true}"));
            rig.updateState(System.currentTimeMillis()+2);
            assertTrue(rig.engine.status().get("blocked").getAsBoolean()); // Trade state must also be registered in ViteApp.
            var acknowledgement = rig.message("execution_entry_state");
            acknowledgement.add("actionId", result.get("actionId")); acknowledgement.addProperty("initialized", true);
            rig.engine.receive(rig.owner, ORIGIN, acknowledgement);
            rig.updateState(System.currentTimeMillis()+3);
            assertFalse(rig.engine.status().get("blocked").getAsBoolean());
            assertTrue(rig.engine.route(entry)); assertEquals(1, rig.mutations.get()); // Pending entry cannot become a second entry.
        }
    }
    @Test void initialEntryPreflightBlocksPositionAndPendingOrderChanges() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) try (var rig = new Rig()) {
            var entry = rig.connectEntry();
            if (scenario == 0) rig.brokerQuantity = 1;
            if (scenario == 1) rig.pendingSymbolOrder = true;
            if (scenario == 2) { rig.pendingSymbolOrder = true; rig.pendingStatus = "NEW_BROKER_STATE"; }
            assertTrue(rig.engine.route(entry)); assertEquals("rejected", rig.finish().get("outcome").getAsString());
            assertEquals(0, rig.mutations.get());
        }
    }
    @Test void insufficientBuyingPowerReachesBrokerWithHalfSizedEntry() throws Exception {
        try (var rig = new Rig()) {
            var entry = rig.connectEntry(); rig.buyingPower = 1; rig.mutationStatus = 400;
            rig.state.getAsJsonObject("entryContext").addProperty("availableBuyingPower", 1);
            rig.updateState(System.currentTimeMillis()); rig.engine.route(entry);
            var result = rig.finish(); assertEquals("rejected", result.get("outcome").getAsString());
            assertEquals("broker HTTP 400", result.get("reason").getAsString());
            assertEquals(1, rig.mutations.get());
            assertEquals(980, rig.bodies.get(0).getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("quantity").getAsDouble());
            assertFalse(result.get("requiresReview").getAsBoolean());
        }
    }
    @Test void fractionalHalfSizedLegsArePassedToBrokerWithoutRounding() throws Exception {
        try (var rig = new Rig()) {
            var entry = rig.connectEntry(); rig.mutationStatus = 400;
            rig.state.getAsJsonObject("entryContext").addProperty("fixedQuantity", 23);
            rig.state.getAsJsonObject("entryContext").addProperty("availableBuyingPower", 200);
            rig.updateState(System.currentTimeMillis()); rig.engine.route(entry);
            assertEquals("rejected", rig.finish().get("outcome").getAsString());
            assertEquals(1, rig.mutations.get());
            var body = rig.bodies.get(0);
            assertEquals(11.5, body.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("quantity").getAsDouble());
            assertEquals(1.5, body.getAsJsonArray("childOrderStrategies").get(0).getAsJsonObject()
                .getAsJsonArray("childOrderStrategies").get(0).getAsJsonObject()
                .getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().get("quantity").getAsDouble());
        }
    }
    @Test void missingEntryLocationRequiresReviewWithoutResubmission() throws Exception {
        try (var rig = new Rig()) {
            var entry = rig.connectEntry(); rig.omitLocation = true;
            rig.engine.route(entry); var result = rig.finish();
            assertEquals("unknown", result.get("outcome").getAsString());
            assertTrue(result.get("requiresReview").getAsBoolean()); assertFalse(result.has("entry"));
            rig.engine.setEnabled(false, false, false); assertTrue(rig.engine.route(entry));
            assertEquals(1, rig.mutations.get());
        }
    }
    @Test void entryStateFailureAndStaleEntryContextBlockFurtherExecution() throws Exception {
        try (var rig = new Rig()) {
            var entry = rig.connectEntry();
            rig.state.getAsJsonObject("entryContext").addProperty("observedAt", System.currentTimeMillis()-15_000);
            rig.updateState(System.currentTimeMillis()-1); assertTrue(rig.engine.route(entry));
            assertEquals(0, rig.mutations.get());
            rig.state.getAsJsonObject("entryContext").addProperty("observedAt", System.currentTimeMillis());
            rig.updateState(System.currentTimeMillis()-1); rig.engine.route(entry); var result = rig.finish();
            var acknowledgement = rig.message("execution_entry_state"); acknowledgement.add("actionId", result.get("actionId"));
            acknowledgement.addProperty("initialized", false); rig.engine.receive(rig.owner, ORIGIN, acknowledgement);
            assertTrue(rig.engine.status().get("requiresReview").getAsBoolean());
            assertTrue(rig.engine.route(entry)); assertEquals(1, rig.mutations.get());
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
            assertTrue(rig.requests.stream().allMatch(request -> request.startsWith("DELETE ")));
            assertTrue(rig.authorizations.stream().allMatch(value -> value.equals("Bearer fake-access-token")));
            assertTrue(rig.engine.route(action("KeyC"))); assertEquals(2, rig.mutations.get());
            rig.state.add("entries", new JsonArray()); rig.updateState(System.currentTimeMillis()+10);
            assertFalse(rig.engine.status().get("blocked").getAsBoolean());
            assertTrue(rig.events.stream().noneMatch(event -> event.toString().contains("fake-access-token")));
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
    @Test void flattenExecutesWithoutBookmapProviderVerification() throws Exception {
        try (var rig = new Rig()) {
            rig.state = fixture("flatten replaces all exit pairs"); rig.connect();
            rig.updateState(System.currentTimeMillis(), 0);
            assertTrue(rig.engine.route(action("KeyF")));
            assertEquals("accepted", rig.finish().get("outcome").getAsString());
            assertEquals(rig.state.getAsJsonArray("pairs").size(), rig.mutations.get());
            assertTrue(rig.bodies.stream().allMatch(body -> body.get("orderType").getAsString().equals("MARKET")));
        }
    }
    @Test void missingExpiredAndNearExpiryTokensAreLeftToBroker() throws Exception {
        for (int scenario = 0; scenario < 3; scenario++) try (var rig = new Rig()) {
            rig.connect(); rig.mutationStatus = 401;
            rig.token(2, System.currentTimeMillis() + (scenario == 0 ? 1000 : -1000), scenario == 2 ? "" : "fake-access-token");
            rig.engine.route(action("KeyC")); var result = rig.finish();
            assertEquals("rejected", result.get("outcome").getAsString());
            assertEquals("broker HTTP 401", result.get("reason").getAsString());
            assertEquals(List.of("DELETE /accounts/hash/orders/101"), rig.requests);
            assertFalse(result.get("requiresReview").getAsBoolean());
        }
    }
    @Test void tokenRefreshDoesNotInterruptRunningCancel() throws Exception {
        try (var rig = new Rig()) {
            rig.connect(); rig.allowMutation = new CountDownLatch(1); rig.engine.route(action("KeyC"));
            assertTrue(rig.mutationEntered.await(3, TimeUnit.SECONDS));
            rig.token(2, System.currentTimeMillis()+120_000, "fake-refreshed-access-token");
            rig.allowMutation.countDown(); assertEquals("accepted", rig.finish().get("outcome").getAsString());
            assertEquals(List.of("Bearer fake-access-token", "Bearer fake-refreshed-access-token"), rig.authorizations);
            assertEquals(2, rig.mutations.get());
        }
    }
    @Test void cancelOfFinishedOrderIsLeftToBroker() throws Exception {
        try (var rig = new Rig()) {
            rig.connect(); rig.orderFilled = true; rig.mutationStatus = 400; rig.engine.route(action("KeyC"));
            assertEquals("rejected", rig.finish().get("outcome").getAsString());
            assertEquals(List.of("DELETE /accounts/hash/orders/101"), rig.requests);
        }
    }
    @Test void staleStateAndPartialExitFillsStillBlockUnintendedExecutions() throws Exception {
        try (var rig = new Rig()) {
            rig.connect(); rig.updateState(System.currentTimeMillis()-20_000);
            rig.engine.route(action("KeyC")); assertEquals(0, rig.requests.size());
            rig.state = fixture("market out smallest first tie"); rig.updateState(System.currentTimeMillis());
            rig.orderFilled = true; rig.engine.route(action("KeyM"));
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
    @Test void untrustedOriginsCannotUpdateCredentialsAndSecondTabCannotOwnSession() throws Exception {
        try (var rig = new Rig()) {
            rig.engine.setEnabled(true, true);
            var hello = json("{\"type\":\"execution_hello\",\"version\":2,\"live\":true,\"broker\":\"Schwab\"}");
            rig.engine.receive(rig.owner, "https://untrusted.example", hello);
            assertEquals("execution_rejected", rig.events.get(0).get("type").getAsString());
            rig.connect(); Object secondTab = new Object(); rig.engine.receive(secondTab, ORIGIN, hello);
            assertEquals("execution_rejected", rig.events.get(rig.events.size()-1).get("type").getAsString());
            rig.token(0, 1); // Older credential generations cannot overwrite a valid token.
            rig.engine.route(action("KeyC")); assertEquals("accepted", rig.finish().get("outcome").getAsString());
        }
    }
}
