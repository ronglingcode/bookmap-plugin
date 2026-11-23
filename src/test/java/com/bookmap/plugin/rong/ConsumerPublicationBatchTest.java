package com.bookmap.plugin.rong;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConsumerPublicationBatchTest {
    @Test void publicationsAreLosslessAndNeverMoreFrequentThanEvery200Millis() {
        List<String> sent = new ArrayList<>(); var batch = new ConsumerPublicationBatch(sent::add);
        for (int i = 0; i < 40_000; i++) { int sequence = i; batch.add(() -> "{\"type\":\"trade\",\"sequence\":" + sequence + "}"); }
        assertTrue(sent.isEmpty()); batch.flush(0); assertEquals(1, sent.size());
        JsonArray events = JsonParser.parseString(sent.get(0)).getAsJsonObject().getAsJsonArray("events");
        assertEquals(40_000, events.size());
        for (int i = 0; i < events.size(); i++) assertEquals(i, events.get(i).getAsJsonObject().get("sequence").getAsInt());
        batch.add("{\"type\":\"wall_removed\"}"); batch.add("{\"type\":\"wall_refilled\"}");
        batch.flush(199); assertEquals(1, sent.size()); batch.flush(200); assertEquals(2, sent.size());
        assertEquals(2, JsonParser.parseString(sent.get(1)).getAsJsonObject().getAsJsonArray("events").size());
    }
    @Test void shutdownFlushesTheLastTradeWithoutWaitingForAnotherMarketEvent() {
        List<String> sent = new ArrayList<>(); var batch = new ConsumerPublicationBatch(sent::add);
        long start = System.nanoTime() / 1_000_000L;
        batch.add("{\"type\":\"first\"}"); batch.flush(start);
        batch.add("{\"type\":\"last_trade\"}"); batch.finish();
        assertEquals(2, sent.size()); assertTrue(System.nanoTime() / 1_000_000L - start >= 200);
        assertTrue(sent.get(1).contains("last_trade"));
    }
    @Test void blockedConsumerDoesNotBlockNewInternalEventsAndFailedPublicationIsRetained() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean fail = new AtomicBoolean(true); List<String> sent = new CopyOnWriteArrayList<>();
        var batch = new ConsumerPublicationBatch(wire -> {
            if (fail.get()) { entered.countDown(); try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                throw new IllegalStateException("consumer unavailable"); }
            sent.add(wire);
        });
        batch.add("{\"type\":\"first\"}");
        var flush = CompletableFuture.runAsync(() -> assertThrows(IllegalStateException.class, () -> batch.flush(0)));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        try { batch.add("{\"type\":\"second\"}"); } finally { release.countDown(); }
        flush.get(3, TimeUnit.SECONDS); fail.set(false); batch.flush(200);
        JsonArray events = JsonParser.parseString(sent.get(0)).getAsJsonObject().getAsJsonArray("events");
        assertEquals("first", events.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("second", events.get(1).getAsJsonObject().get("type").getAsString());
    }
    @Test void internalCandlesAndSnapshotsAreCurrentBeforePublicationAndResetOrderingIsPreserved() {
        List<String> sent = new ArrayList<>(); var batch = new ConsumerPublicationBatch(sent::add);
        CairoLiveFeed feed = new CairoLiveFeed(sent::add, batch);
        long time = java.time.Instant.parse("2026-11-23T14:30:00Z").toEpochMilli() * 1_000_000L;
        feed.chart("NVDA", "replay", true);
        for (int i = 0; i < 40_000; i++) feed.observeTrade("NVDA", "replay", 100 + i % 3, time + i, 2, null);
        JsonObject bars = new JsonObject(); feed.minuteBars(bars, "NVDA", "replay");
        assertEquals(80_000, bars.getAsJsonArray("bars").get(0).getAsJsonArray().get(5).getAsLong());
        assertTrue(sent.isEmpty()); List<String> snapshot = new ArrayList<>(); feed.snapshot(snapshot::add);
        JsonObject latest = JsonParser.parseString(snapshot.get(snapshot.size() - 1)).getAsJsonObject();
        assertEquals(40_000, latest.get("tradeSequence").getAsInt());
        feed.replay(JsonParser.parseString("{\"type\":\"cairo_replay_reset\",\"symbol\":\"NVDA\"}").getAsJsonObject());
        feed.observeTrade("NVDA", "replay", 99, time, 7, null);
        batch.flush(0);
        JsonArray events = JsonParser.parseString(sent.get(0)).getAsJsonObject().getAsJsonArray("events");
        assertEquals(40_003, events.size()); assertEquals("cairo_replay_reset", events.get(40_001).getAsJsonObject().get("type").getAsString());
        for (int i = 0; i < 40_000; i++) assertEquals(i + 1, events.get(i + 1).getAsJsonObject().get("tradeSequence").getAsInt());
        assertEquals(7, events.get(40_002).getAsJsonObject().get("quantity").getAsInt());
    }
}
