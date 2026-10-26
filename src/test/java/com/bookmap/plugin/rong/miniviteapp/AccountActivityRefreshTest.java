package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.runtime.TradingRuntime;
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

class AccountActivityRefreshTest {
    static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    static class Task { long delay; Runnable run; boolean canceled; }
    static class Socket implements SocketPort.Connection { SocketPort.Handlers handlers; boolean closed; final List<String> sent = new ArrayList<>(); public void send(String value) { sent.add(value); } public void close() { closed = true; } }
    @Test void accountActivityRefreshesImmediatelyAndCoalescesHintsDuringRead() throws Exception {
        JsonArray fixtures = JsonParser.parseReader(new InputStreamReader(getClass().getResourceAsStream("/state-fixtures.json"), StandardCharsets.UTF_8)).getAsJsonArray();
        JsonObject config = null, saved = null;
        for (JsonElement item : fixtures) {
            JsonObject fixture = item.getAsJsonObject();
            if (fixture.get("name").getAsString().equals("two selected stocks config")) config = fixture.getAsJsonArray("args").get(0).getAsJsonObject().deepCopy();
            if (fixture.get("name").getAsString().equals("two stocks retain independent long and short entry state")) saved = fixture.getAsJsonObject("result");
        }
        assertNotNull(config); assertNotNull(saved);
        final JsonObject configData = config, savedState = saved;
        long now = Instant.parse("2026-10-01T13:32:00Z").toEpochMilli();
        List<Socket> sockets = new ArrayList<>(); List<Runnable> queued = new ArrayList<>();
        AtomicInteger accountReads = new AtomicInteger();
        JsonObject secrets = json("{\"access_token\":\"fake-token\",\"accountHashValue\":\"fake-account\",\"expires_at\":9999999999999}");
        HttpPort http = (uri, method, headers, body) -> {
            String path = uri.getPath();
            if (path.endsWith(":runQuery")) {
                JsonObject document = new JsonObject(); document.add("fields", DocumentCodec.encodeFields(configData));
                JsonObject row = new JsonObject(); row.add("document", document); JsonArray rows = new JsonArray(); rows.add(row);
                return new HttpPort.Response(200, rows.toString());
            }
            if (path.endsWith("/tradingState")) { JsonObject document = new JsonObject(); document.add("fields", DocumentCodec.encodeFields(savedState)); return new HttpPort.Response(200, document.toString()); }
            if (path.endsWith("/accounts")) {
                accountReads.incrementAndGet();
                return new HttpPort.Response(200, "[{\"securitiesAccount\":{\"positions\":[{\"instrument\":{\"symbol\":\"AAPL\",\"assetType\":\"EQUITY\"},\"longQuantity\":90,\"averagePrice\":10},{\"instrument\":{\"symbol\":\"TSLA\",\"assetType\":\"EQUITY\"},\"shortQuantity\":40,\"averagePrice\":10}],\"currentBalances\":{\"liquidationValue\":25000}}}]");
            }
            if (path.endsWith("/orders")) return new HttpPort.Response(200, "[]");
            if (path.endsWith("/userPreference")) return new HttpPort.Response(200, "{\"streamerInfo\":[{\"streamerSocketUrl\":\"wss://fake.invalid\",\"schwabClientCustomerId\":\"customer\",\"schwabClientCorrelId\":\"correl\",\"schwabClientChannel\":\"channel\",\"schwabClientFunctionId\":\"function\"}]}");
            if (path.contains("/reference/")) return new HttpPort.Response(200, "{\"results\":{\"weighted_shares_outstanding\":1000000000}}");
            if (path.contains("/v3/trades/")) return new HttpPort.Response(200, "{\"results\":[]}");
            if (path.contains("/aggs/")) return new HttpPort.Response(200, "{\"results\":[{\"t\":1790859600000,\"o\":10,\"h\":11,\"l\":9,\"c\":10,\"v\":1000000,\"vw\":10},{\"t\":1790861400000,\"o\":10,\"h\":11,\"l\":9,\"c\":10,\"v\":2000000,\"vw\":10}]}");
            throw new AssertionError("Unexpected route " + path);
        };
        try (TradingRuntime runtime = new TradingRuntime(http, new CredentialPort() {
            public JsonObject loadSchwab() { return secrets; }
            public void saveSchwab(JsonObject value) { }
        }, json("{\"firebaseConfig\":{\"projectId\":\"fake-project\"},\"massive\":{\"apiKey\":\"fake-massive\"}}"),
                (url, handlers) -> { Socket socket = new Socket(); socket.handlers = handlers; sockets.add(socket); return socket; },
                (delay, run) -> () -> {}, queued::add, () -> now, new TradingRuntime.Events() {
                    public void message(JsonObject value) { }
                    public void log(String symbol, String value) { }
                    public void notify(String symbol, String value) { }
                })) {
            CompletableFuture<Void> first = runtime.start();
            while (!queued.isEmpty()) queued.remove(0).run(); first.join();
            assertEquals(2, sockets.size());
            runtime.refreshAccount();
            while (!queued.isEmpty()) queued.remove(0).run();
            assertEquals(2, accountReads.get());
            runtime.refreshAccount();
            assertTrue(queued.isEmpty(), "Ordinary polling remains throttled");
            Socket schwab = sockets.get(1);
            String activity = "{\"data\":[{\"service\":\"ACCT_ACTIVITY\",\"content\":[{\"2\":\"ExecutionCreated\"}]}]}";
            schwab.handlers.message(schwab, activity);
            assertFalse(queued.isEmpty(), "Broker activity bypasses the ordinary throttle");
            // Hints received while a read is queued/in flight must produce one
            // fresh read afterwards, rather than disappearing behind that read.
            schwab.handlers.message(schwab, activity); schwab.handlers.message(schwab, activity);
            while (!queued.isEmpty()) queued.remove(0).run();
            assertEquals(4, accountReads.get(), "In-flight activity hints coalesce into one urgent follow-up");
        }
    }
}
