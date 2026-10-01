package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.OAuth;
import com.bookmap.plugin.rong.miniviteapp.ports.CredentialPort;
import com.bookmap.plugin.rong.miniviteapp.ports.HttpPort;
import com.bookmap.plugin.rong.miniviteapp.runtime.LocalCredentials;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LocalCredentialsTest {
    @TempDir Path directory;
    @Test void rotationSurvivesRestartAndPreservesOtherSecrets() throws Exception {
        Path file = directory.resolve("secrets.json");
        Files.writeString(file, "\uFEFF{\"massive\":{\"apiKey\":\"test-massive\"},\"firebaseConfig\":{\"projectId\":\"test\"},\"extra\":true,\"schwab\":{\"appKey\":\"test\",\"secret\":\"test\",\"refresh_token\":\"old\",\"custom\":1}}");
        LocalCredentials credentials = new LocalCredentials(file);
        OAuth oauth = new OAuth((uri, method, headers, body) -> new HttpPort.Response(200,
                "{\"access_token\":\"new\",\"refresh_token\":\"rotated\",\"expires_in\":1800}"), credentials,
                URI.create("https://api.schwabapi.com/v1/oauth/token"), () -> 100000);
        oauth.refresh();
        LocalCredentials restarted = new LocalCredentials(file);
        assertEquals("rotated", restarted.loadSchwab().get("refresh_token").getAsString());
        assertEquals(1900000, restarted.loadSchwab().get("expires_at").getAsLong());
        assertEquals(1, restarted.loadSchwab().get("custom").getAsInt());
        assertEquals("test-massive", restarted.section("massive").get("apiKey").getAsString());
        assertTrue(JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("extra").getAsBoolean());
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
    @Test void concurrentExpiredTokenCallersRefreshOnce() throws Exception {
        int count = 8; CountDownLatch readPrevious = new CountDownLatch(count);
        ThreadLocal<Integer> reads = ThreadLocal.withInitial(() -> 0);
        AtomicReference<JsonObject> stored = new AtomicReference<>(JsonParser.parseString(
                "{\"appKey\":\"test\",\"secret\":\"test\",\"refresh_token\":\"old\",\"access_token\":\"old\",\"expires_at\":0}").getAsJsonObject());
        CredentialPort credentials = new CredentialPort() {
            public JsonObject loadSchwab() {
                int current = reads.get() + 1; reads.set(current); JsonObject result = stored.get().deepCopy();
                if (current == 2) readPrevious.countDown(); return result;
            }
            public void saveSchwab(JsonObject value) { stored.set(value.deepCopy()); }
        };
        AtomicInteger calls = new AtomicInteger();
        OAuth oauth = new OAuth((uri, method, headers, body) -> {
            calls.incrementAndGet(); assertTrue(readPrevious.await(5, TimeUnit.SECONDS));
            return new HttpPort.Response(200, "{\"access_token\":\"new\",\"refresh_token\":\"rotated\",\"expires_in\":1800}");
        }, credentials, URI.create("https://api.schwabapi.com/v1/oauth/token"), () -> 100000);
        var executor = Executors.newFixedThreadPool(count);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < count; i++) results.add(executor.submit(() -> oauth.accessToken(false)));
            for (Future<String> result : results) assertEquals("new", result.get(10, TimeUnit.SECONDS));
            assertEquals(1, calls.get()); assertEquals("rotated", stored.get().get("refresh_token").getAsString());
        } finally { executor.shutdownNow(); }
    }
}
