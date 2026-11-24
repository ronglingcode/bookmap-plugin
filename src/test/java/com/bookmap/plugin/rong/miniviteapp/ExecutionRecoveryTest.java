package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.runtime.*;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionRecoveryTest {
    @Test void urgentExitSupersedesQueuedEntriesAndOtherSymbolsKeepRunning() throws Exception {
        try (SymbolCommandQueue queue = new SymbolCommandQueue()) {
            CompletableFuture<Void> inFlight = new CompletableFuture<>();
            CountDownLatch entered = new CountDownLatch(1); List<String> order = new CopyOnWriteArrayList<>();
            var first = queue.submit("A", 1, () -> { entered.countDown(); return inFlight; });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var entry = queue.submit("A", 2, () -> { fail("Superseded entry must not execute"); return null; });
            var adjustment = queue.submit("A", 1, () -> { order.add("adjustment"); return CompletableFuture.completedFuture(null); });
            queue.submit("B", 2, () -> { order.add("other symbol"); return CompletableFuture.completedFuture(null); }).get(2, TimeUnit.SECONDS);
            var exit = queue.submit("A", 0, () -> { order.add("exit"); return CompletableFuture.completedFuture(null); });
            assertTrue(entry.isCompletedExceptionally()); assertEquals(List.of("other symbol"), order);
            inFlight.complete(null); first.get(2, TimeUnit.SECONDS); exit.get(2, TimeUnit.SECONDS); adjustment.get(2, TimeUnit.SECONDS);
            assertEquals(List.of("other symbol", "exit", "adjustment"), order);
        }
    }
    @Test void overloadIsBoundedAndFailuresDoNotPoisonTheLane() throws Exception {
        try (SymbolCommandQueue queue = new SymbolCommandQueue()) {
            CompletableFuture<Void> hold = new CompletableFuture<>(); CountDownLatch entered = new CountDownLatch(1);
            queue.submit("A", 1, () -> { entered.countDown(); return hold; }); assertTrue(entered.await(2, TimeUnit.SECONDS));
            List<CompletableFuture<Void>> pending = new ArrayList<>();
            for (int i = 0; i < 8; i++) pending.add(queue.submit("A", 1, () -> CompletableFuture.completedFuture(null)));
            assertEquals(8, queue.pendingCount());
            assertTrue(queue.submit("A", 1, () -> null).isCompletedExceptionally());
            var exit = queue.submit("A", 0, () -> { throw new IllegalStateException("Broker offline"); });
            hold.complete(null); assertThrows(ExecutionException.class, () -> exit.get(2, TimeUnit.SECONDS));
            queue.submit("A", 1, () -> CompletableFuture.completedFuture(null)).get(2, TimeUnit.SECONDS);
        }
    }
    @Test void uncertainOpeningResolvesOnlyAgainstOneNewMatchingBrokerOrder() {
        OpeningReconciliation recovery = new OpeningReconciliation();
        JsonObject submitted = JsonParser.parseString("{\"orderType\":\"LIMIT\",\"price\":10,\"orderLegCollection\":[{\"quantity\":25,\"instruction\":\"BUY\",\"instrument\":{\"symbol\":\"A\"}}]}").getAsJsonObject();
        JsonObject old = submitted.deepCopy(); old.addProperty("orderId", 1);
        JsonArray before = new JsonArray(); before.add(old); recovery.observe(before);
        var intent = recovery.begin("A", submitted); recovery.complete(intent, true);
        recovery.observe(before); assertTrue(recovery.unresolved("A")); assertFalse(recovery.unresolved("B"));
        JsonObject unrelated = submitted.deepCopy(); unrelated.addProperty("orderId", 2); unrelated.addProperty("price", 11);
        JsonArray after = before.deepCopy(); after.add(unrelated); recovery.observe(after); assertTrue(recovery.unresolved("A"));
        JsonObject matched = submitted.deepCopy(); matched.addProperty("orderId", 3); matched.addProperty("status", "FILLED"); matched.addProperty("price", "10.00");
        after.add(matched); recovery.observe(after); assertFalse(recovery.unresolved("A"));
        var rejected = recovery.begin("A", submitted); recovery.complete(rejected, false); assertFalse(recovery.unresolved("A"));
    }
    @Test void freshReadCannotReuseAnObservationStartedBeforeTheMutation() throws Exception {
        FreshRead<Integer> reads = new FreshRead<>(); AtomicInteger count = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> reads.read(0, () -> { int value = count.incrementAndGet(); entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); return value; }));
            assertTrue(entered.await(2, TimeUnit.SECONDS)); long afterMutation = System.nanoTime();
            var fresh = pool.submit(() -> reads.read(afterMutation, count::incrementAndGet));
            release.countDown(); assertEquals(1, first.get(2, TimeUnit.SECONDS)); assertEquals(2, fresh.get(2, TimeUnit.SECONDS));
            assertEquals(2, count.get());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
