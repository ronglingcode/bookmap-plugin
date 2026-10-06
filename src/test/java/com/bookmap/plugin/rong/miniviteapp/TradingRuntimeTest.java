package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.runtime.TradingRuntime;
import com.bookmap.plugin.rong.NativeConnectionStatus;
import com.bookmap.plugin.rong.miniviteapp.core.account.ExecutionExports.Format;
import com.bookmap.plugin.rong.miniviteapp.ports.*;
import com.bookmap.plugin.rong.miniviteapp.libraries.firestore.DocumentCodec;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradingRuntimeTest {
    static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    static class Task { long delay; Runnable run; boolean canceled; }
    static class Socket implements SocketPort.Connection { SocketPort.Handlers handlers; boolean closed; final List<String> sent = new ArrayList<>(); public void send(String value) { sent.add(value); } public void close() { closed = true; } }
    @Test void invalidSelectedPlanStopsBeforeVendorConnectionsAndUpdatesEveryStatus() throws Exception {
        JsonArray fixtures = JsonParser.parseReader(new InputStreamReader(getClass().getResourceAsStream("/state-fixtures.json"), StandardCharsets.UTF_8)).getAsJsonArray();
        JsonObject config = null;
        for (JsonElement item : fixtures) {
            JsonObject fixture = item.getAsJsonObject();
            if (fixture.get("name").getAsString().equals("stock selections config"))
                config = fixture.getAsJsonArray("args").get(0).getAsJsonObject().deepCopy();
        }
        assertNotNull(config);
        JsonObject plan = config.getAsJsonArray("plans").get(0).getAsJsonObject();
        plan.addProperty("symbol", "PCVX");
        plan.getAsJsonObject("long").addProperty("enabled", false);
        JsonObject shortPlan = plan.getAsJsonObject("short");
        shortPlan.addProperty("enabled", true);
        shortPlan.addProperty("firstTargetToAdd", "0");
        JsonArray selections = new JsonArray(); selections.add("PCVX"); config.add("stockSelections", selections);
        JsonObject document = new JsonObject(); document.add("fields", DocumentCodec.encodeFields(config));
        JsonObject row = new JsonObject(); row.add("document", document);
        JsonArray rows = new JsonArray(); rows.add(row);
        AtomicInteger configReads = new AtomicInteger();
        HttpPort http = (uri, method, headers, body) -> {
            assertTrue(uri.getPath().endsWith(":runQuery"), "Invalid plan must stop startup before broker or market requests");
            configReads.incrementAndGet(); return new HttpPort.Response(200, rows.toString());
        };
        CredentialPort credentials = new CredentialPort() {
            public JsonObject loadSchwab() { return new JsonObject(); }
            public void saveSchwab(JsonObject value) { fail("Invalid plan must not rotate tokens"); }
        };
        NativeConnectionStatus status = new NativeConnectionStatus(); status.setStarting();
        List<String> logs = new ArrayList<>();
        List<JsonObject> emitted = new ArrayList<>();
        try (TradingRuntime runtime = new TradingRuntime(http, credentials,
                json("{\"firebaseConfig\":{\"projectId\":\"fake-project\"},\"massive\":{\"apiKey\":\"fake-massive\"}}"),
                (url, handlers) -> { throw new AssertionError("Invalid plan must not open streams"); },
                (delay, task) -> { throw new AssertionError("Invalid plan must not start timers"); },
                Runnable::run, System::currentTimeMillis, new TradingRuntime.Events() {
                    public void message(JsonObject value) { emitted.add(value.deepCopy()); }
                    public void log(String symbol, String value) { logs.add(value); }
                    public void notify(String symbol, String value) { fail("Unexpected notification"); }
                    public void status(String source, String value) { status.update(source, value); }
                })) {
            CompletionException error = assertThrows(CompletionException.class, () -> runtime.start().join());
            assertEquals("PCVX missing first target to add", error.getCause().getMessage());
            assertTrue(emitted.isEmpty(), "Failed startup must not publish trading state");
            runtime.dispatch(json("{\"symbol\":\"PCVX\",\"keyCode\":\"KeyB\"}"));
            assertTrue(emitted.stream().anyMatch(value -> value.get("type").getAsString().equals("execution_blocked")));
            assertEquals(1, configReads.get());
            assertEquals("startup failed", status.snapshot().getSchwab());
            assertEquals("startup failed", status.snapshot().getMassiveHistory());
            assertEquals("startup failed", status.snapshot().getMassiveStream());
            assertTrue(logs.stream().anyMatch(value -> value.contains("PCVX missing first target to add")));
        }
    }
    @Test void standaloneStartupRenewalAcceptedEntryPersistenceAndTeardown() throws Exception {
        JsonArray fixtures = JsonParser.parseReader(new InputStreamReader(getClass().getResourceAsStream("/state-fixtures.json"), StandardCharsets.UTF_8)).getAsJsonArray();
        JsonObject config = null, saved = null;
        for (JsonElement item : fixtures) { JsonObject f = item.getAsJsonObject(); if (f.get("name").getAsString().equals("stock selections config")) config = f.getAsJsonArray("args").get(0).getAsJsonObject(); if (f.get("name").getAsString().equals("new state and accepted entry")) saved = f.getAsJsonObject("result"); }
        final JsonObject configData = config.deepCopy(), savedState = saved;
        configData.getAsJsonArray("plans").get(0).getAsJsonObject().getAsJsonObject("long").addProperty("firstTargetToAdd", "-1");
        AtomicLong now = new AtomicLong(Instant.parse("2026-10-01T13:32:00Z").toEpochMilli()); AtomicInteger refreshes = new AtomicInteger(), accountReads = new AtomicInteger(), writes = new AtomicInteger(), mutations = new AtomicInteger(), audits = new AtomicInteger();
        AtomicBoolean limited = new AtomicBoolean(); AtomicReference<String> positionData = new AtomicReference<>("[]"), orderData = new AtomicReference<>("[]");
        AtomicInteger httpCalls = new AtomicInteger();
        List<String> canceledOrders = new CopyOnWriteArrayList<>();
        AtomicReference<JsonObject> secrets = new AtomicReference<>(json("{\"appKey\":\"fake-app\",\"secret\":\"fake-secret\",\"access_token\":\"expired\",\"refresh_token\":\"fake-refresh\",\"expires_at\":0,\"accountHashValue\":\"fake-account\"}"));
        List<JsonObject> emitted = new CopyOnWriteArrayList<>(); List<String> logs = new CopyOnWriteArrayList<>(); List<Task> tasks = new ArrayList<>(); List<Socket> sockets = new ArrayList<>();
        List<String> screen = new CopyOnWriteArrayList<>();
        HttpPort http = (uri, method, headers, body) -> {
            httpCalls.incrementAndGet();
            assertFalse(uri.toString().contains("localhost")); String path = uri.getPath();
            if (method.equals("DELETE")) { canceledOrders.add(path); return new HttpPort.Response(204, ""); }
            if (path.endsWith("/oauth/token")) { int count = refreshes.incrementAndGet(); return new HttpPort.Response(200, "{\"access_token\":\"fresh-" + count + "\",\"refresh_token\":\"rotated-" + count + "\",\"expires_in\":1800}"); }
            if (path.endsWith(":runQuery")) { JsonObject document = new JsonObject(); document.add("fields", DocumentCodec.encodeFields(configData)); JsonObject row = new JsonObject(); row.add("document", document); JsonArray rows = new JsonArray(); rows.add(row); return new HttpPort.Response(200, rows.toString()); }
            if (path.endsWith("/tradingState")) { if (method.equals("PATCH")) { writes.incrementAndGet(); assertEquals(savedState.get("date"), DocumentCodec.decodeFields(json(body).getAsJsonObject("fields")).get("date")); return new HttpPort.Response(200, "{}"); } JsonObject document = new JsonObject(); document.add("fields", DocumentCodec.encodeFields(savedState)); return new HttpPort.Response(200, document.toString()); }
            String account = "{\"positions\":" + positionData.get() + ",\"currentBalances\":{\"liquidationValue\":25000}}";
            if (path.endsWith("/accounts")) { accountReads.incrementAndGet(); if (limited.get()) return new HttpPort.Response(429, "", Map.of("Retry-After", "60")); return new HttpPort.Response(200, "[{\"securitiesAccount\":" + account + "}]"); }
            if (path.endsWith("/accounts/fake-account")) return new HttpPort.Response(200, "{\"securitiesAccount\":" + account + "}");
            if (path.endsWith("/orders")) { if (method.equals("POST")) { mutations.incrementAndGet(); return new HttpPort.Response(201, "", Map.of("Location", "https://fake.invalid/orders/123")); } return new HttpPort.Response(200, orderData.get()); }
            if (path.endsWith("/userPreference")) return new HttpPort.Response(200, "{\"streamerInfo\":[{\"streamerSocketUrl\":\"wss://fake.invalid\",\"schwabClientCustomerId\":\"customer\",\"schwabClientCorrelId\":\"correl\",\"schwabClientChannel\":\"channel\",\"schwabClientFunctionId\":\"function\"}]}");
            if (path.contains("/reference/")) return new HttpPort.Response(200, "{\"results\":{\"weighted_shares_outstanding\":1000000000}}");
            if (path.contains("/v3/trades/")) return new HttpPort.Response(200, "{\"results\":[]}");
            if (path.contains("/aggs/")) return new HttpPort.Response(200, "{\"results\":[{\"t\":1790859600000,\"o\":10,\"h\":11,\"l\":9,\"c\":10,\"v\":1000000,\"vw\":10},{\"t\":1790861400000,\"o\":10,\"h\":11,\"l\":9,\"c\":10,\"v\":2000000,\"vw\":10}]}");
            if (path.endsWith("-Logs") || path.endsWith("-Orders") || path.endsWith("/BreakoutTradeState")) { audits.incrementAndGet(); assertFalse(body.contains("fake-secret") || body.contains("fake-refresh")); return new HttpPort.Response(201, "{}"); }
            throw new AssertionError("Unexpected route " + path);
        };
        CredentialPort credentials = new CredentialPort() { public JsonObject loadSchwab() { return secrets.get().deepCopy(); } public void saveSchwab(JsonObject value) { secrets.set(value.deepCopy()); } };
        try (TradingRuntime runtime = new TradingRuntime(http, credentials, json("{\"firebaseConfig\":{\"projectId\":\"fake-project\",\"apiKey\":\"fake-firebase\"},\"massive\":{\"apiKey\":\"fake-massive\"}}"),
            (url, handlers) -> { Socket socket = new Socket(); socket.handlers = handlers; sockets.add(socket); return socket; },
            (delay, run) -> { Task task = new Task(); task.delay = delay; task.run = run; tasks.add(task); return () -> task.canceled = true; }, Runnable::run, now::get,
            new TradingRuntime.Events() {
                public void message(JsonObject value) { emitted.add(value.deepCopy()); }
                public void log(String symbol, String value) { logs.add(value); screen.add(value); }
                public void detail(String symbol, String value) { logs.add(value); }
                public void summary(String symbol, String value, String screenMessage) { logs.add(value); screen.add(screenMessage); }
                public void notify(String symbol, String value) { log(symbol, value); }
            })) {
            assertThrows(IllegalStateException.class, () -> runtime.exportExecutions(Format.CSV));
            runtime.start().get(2, TimeUnit.SECONDS); assertEquals(1, refreshes.get()); assertEquals(1, accountReads.get()); assertEquals(2, sockets.size());
            assertTrue(logs.stream().anyMatch(value -> value.contains("Native broker orders: sessionDate=2026-10-01 fetched=0")));
            assertTrue(logs.stream().anyMatch(value -> value.contains("Native projected fills available for display: sessionDate=2026-10-01 count=0")));
            assertThrows(IllegalStateException.class, () -> runtime.exportExecutions(Format.CSV));
            JsonObject view = runtime.view("AAPL", "test"); assertEquals(100, view.getAsJsonObject("state").getAsJsonObject("stateBySymbol").getAsJsonObject("AAPL").getAsJsonObject("breakoutTradeStateForLong").get("initialQuantity").getAsInt());
            JsonObject targets = runtime.targetMarket("AAPL");
            assertNotNull(targets);
            assertEquals("2026-10-01", targets.get("sessionDate").getAsString());
            assertEquals(view.getAsJsonObject("plan").getAsJsonObject("atr").get("average"), targets.get("atr"));
            assertTrue(targets.get("lowOfDay").getAsDouble() > 0);
            runtime.persistState(); runtime.pendingPersistence().get(2, TimeUnit.SECONDS); assertEquals(1, writes.get());
            now.addAndGet(1800000); runtime.refreshToken(); assertEquals(2, refreshes.get()); runtime.refreshAccount(); assertEquals(2, accountReads.get());
            // A real native decision and fake broker acceptance initialize state locally, without a ViteApp ACK.
            JsonObject action = json("{\"symbol\":\"AAPL\",\"tradebook_id\":\"GapGiveAndGoBookmapReversal\",\"entry_method\":\"0.1 R\",\"use_market_order\":true,\"price\":10}"); runtime.dispatch(action);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2); while (emitted.stream().noneMatch(event -> event.has("outcome")) && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(1, mutations.get(), logs.toString()); assertTrue(emitted.stream().anyMatch(event -> event.has("outcome") && event.get("outcome").getAsString().equals("accepted")), emitted.toString());
            runtime.pendingPersistence().get(2, TimeUnit.SECONDS); assertEquals(2, writes.get());
            runtime.dispatch(json("{\"type\":\"manual_inputs\",\"symbol\":\"AAPL\",\"customEntryPrice\":11,\"fixedQuantity\":20}")); assertEquals(20, runtime.manualInputs("AAPL").get("fixedQuantity").getAsInt()); runtime.dispatch(json("{\"symbol\":\"AAPL\",\"keyCode\":\"Space\"}")); assertEquals(json("{\"fixedQuantity\":20}"), runtime.manualInputs("AAPL"));
            for (int i = 0; i < 10; i++) runtime.refreshAccount(); assertEquals(2, accountReads.get());
            assertEquals(1, tasks.stream().filter(task -> task.delay == 3000 && !task.canceled).count()); Task deferred = tasks.stream().filter(task -> task.delay == 3000 && !task.canceled).findFirst().orElseThrow(); deferred.canceled = true; limited.set(true); now.addAndGet(3000); deferred.run.run(); assertEquals(3, accountReads.get());
            runtime.refreshAccount(); assertEquals(3, accountReads.get()); Task retry = tasks.stream().filter(task -> task.delay == 60000 && !task.canceled).reduce((a, b) -> b).orElseThrow(); retry.canceled = true; limited.set(false); now.addAndGet(60000); retry.run.run(); assertEquals(4, accountReads.get());
            positionData.set("[{\"instrument\":{\"symbol\":\"MSFT\",\"assetType\":\"EQUITY\"},\"longQuantity\":10,\"shortQuantity\":0,\"averagePrice\":20}]"); now.addAndGet(3000); runtime.refreshAccount(); assertEquals(10, runtime.view("MSFT", "test").getAsJsonObject("account").getAsJsonObject("positions").getAsJsonObject("MSFT").get("netQuantity").getAsInt());
            positionData.set("[]"); now.addAndGet(3000); runtime.refreshAccount(); assertTrue(emitted.stream().anyMatch(event -> event.has("symbol") && event.get("symbol").getAsString().equals("MSFT") && event.get("type").getAsString().equals("account_ready") && event.getAsJsonObject("account").getAsJsonObject("positions").size() == 0));
            configData.add("stockSelections", new JsonArray()); runtime.refreshConfig(); assertFalse(runtime.view("AAPL", "test").has("plan")); assertEquals(0, audits.get(), "Logs, notifications, orders and breakout snapshots must never be sent to Firestore");
            // Reporting includes all symbols after watchlist removal and never reads the broker.
            JsonArray orders = new JsonArray();
            for (String symbol : List.of("AAPL", "MSFT")) {
                JsonObject order = json("{\"orderStrategyType\":\"SINGLE\",\"orderType\":\"MARKET\",\"status\":\"FILLED\",\"orderId\":\"42\",\"orderLegCollection\":[{\"legId\":1,\"orderLegType\":\"EQUITY\",\"instruction\":\"BUY\",\"positionEffect\":\"OPENING\",\"instrument\":{\"assetType\":\"EQUITY\"}}],\"orderActivityCollection\":[{\"activityType\":\"EXECUTION\",\"executionType\":\"FILL\",\"executionLegs\":[{\"legId\":1,\"time\":\"2026-10-01T13:31:00Z\",\"quantity\":10,\"price\":20}]}]}");
                order.addProperty("orderId", symbol.equals("AAPL") ? "42" : "43");
                order.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().getAsJsonObject("instrument").addProperty("symbol", symbol); orders.add(order);
            }
            orderData.set(orders.toString()); now.addAndGet(3000); runtime.refreshAccount();
            assertTrue(logs.stream().anyMatch(value -> value.contains("Native broker orders: sessionDate=2026-10-01 fetched=2")));
            assertTrue(logs.stream().anyMatch(value -> value.contains("Native projected fills available for display: sessionDate=2026-10-01 count=2")));
            assertTrue(logs.stream().anyMatch(value -> value.contains("Native projected fill: symbol=MSFT orderId=43")));
            assertTrue(screen.contains("Account fills available for display: 2 for 2026-10-01"));
            assertTrue(screen.stream().noneMatch(value -> value.contains("Native broker order")
                    || value.contains("Native projected fill") || value.contains("execution time sample")
                    || value.contains("returned HTTP") || value.contains("Native entry POST")), screen.toString());
            int callsBeforeExport = httpCalls.get(); JsonObject beforeExport = runtime.view("MSFT", "test");
            for (Format format : Format.values()) { String report = runtime.exportExecutions(format); assertTrue(report.contains("AAPL")); assertTrue(report.contains("MSFT")); }
            assertEquals(callsBeforeExport, httpCalls.get()); assertEquals(beforeExport, runtime.view("MSFT", "test"));
            long sameDay = now.get(); now.addAndGet(86400000);
            assertThrows(IllegalStateException.class, () -> runtime.exportExecutions(Format.SUMMARY), "Yesterday's cached fills must not be exported as today"); now.set(sameDay);
            runtime.pendingPersistence().get(2, TimeUnit.SECONDS);
            // The cache has only terminal fills. Cancel must discover a newly submitted broker order.
            orderData.set("[{\"orderId\":\"456\",\"orderStrategyType\":\"TRIGGER\",\"orderType\":\"STOP\",\"status\":\"WORKING\",\"cancelable\":true,\"quantity\":10,\"price\":9,\"orderLegCollection\":[{\"instruction\":\"BUY\",\"instrument\":{\"symbol\":\"AAPL\",\"assetType\":\"EQUITY\"}}]}]");
            runtime.dispatch(json("{\"symbol\":\"AAPL\",\"keyCode\":\"KeyC\"}"));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (canceledOrders.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(List.of("/trader/v1/accounts/fake-account/orders/456"), canceledOrders);
            Socket schwab = sockets.get(sockets.size() - 1);
            schwab.handlers.message(schwab, "{\"response\":[{\"service\":\"ADMIN\",\"command\":\"LOGIN\",\"content\":{\"code\":0}}]}");
            assertTrue(schwab.sent.stream().anyMatch(value -> value.contains("ACCT_ACTIVITY") && value.contains("0,1,2,3")));
            int hints = (int) emitted.stream().filter(event -> event.get("type").getAsString().equals("cairo_account_activity")).count();
            schwab.handlers.message(schwab, "{\"data\":[{\"service\":\"ACCT_ACTIVITY\",\"content\":[{\"1\":\"private-account\",\"2\":\"ExecutionCreated\",\"3\":\"private-xml\"}]}]}");
            List<JsonObject> activities = new ArrayList<>();
            emitted.stream().filter(event -> event.get("type").getAsString().equals("cairo_account_activity")).forEach(activities::add);
            assertEquals(hints + 1, activities.size());
            JsonObject activity = activities.get(activities.size() - 1);
            assertEquals(now.get(), activity.get("receivedAt").getAsLong());
            assertEquals("ExecutionCreated", activity.getAsJsonArray("messageTypes").get(0).getAsString());
            assertFalse(activity.toString().contains("private-") || activity.toString().contains("fresh-") || activity.toString().contains("fake-secret"));
            orderData.set("invalid JSON");
            runtime.dispatch(json("{\"symbol\":\"AAPL\",\"keyCode\":\"KeyC\"}"));
            assertEquals(1, canceledOrders.size());
            assertTrue(emitted.stream().anyMatch(event -> event.has("reason") && event.get("reason").getAsString().contains("no cancellation attempted")));
            runtime.close(); assertTrue(sockets.stream().allMatch(socket -> socket.closed)); assertTrue(tasks.stream().allMatch(task -> task.canceled)); int readCount = accountReads.get(); tasks.forEach(task -> task.run.run()); runtime.refreshAccount(); assertEquals(readCount, accountReads.get());
            assertTrue(logs.stream().noneMatch(value -> value.contains("fake-secret") || value.contains("fake-refresh")));
        }
    }
}

