package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.runtime.*;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BackgroundResilienceTest {
    private static JsonObject version(int value) { JsonObject state = new JsonObject(); state.addProperty("version", value); return state; }
    @Test void failedCloudWriteRetriesTheLatestSnapshotAndCompletesOnlyAfterSuccess() throws Exception {
        List<Runnable> jobs = new ArrayList<>(), timers = new ArrayList<>(); List<Long> delays = new ArrayList<>();
        List<Integer> attempted = new ArrayList<>(); AtomicInteger failures = new AtomicInteger();
        try (var writer = new CoalescingStateWriter(jobs::add, (delay, task) -> { delays.add(delay); timers.add(task); return () -> timers.remove(task); },
                (profile, state) -> { attempted.add(state.get("version").getAsInt()); if (attempted.size() == 1) throw new java.io.IOException("Cloud unavailable"); }, error -> failures.incrementAndGet())) {
            writer.put("schwab", version(1)); writer.put("schwab", version(2)); writer.put("schwab", version(3));
            CompletableFuture<Void> pending = writer.pending(); assertEquals(1, jobs.size());
            jobs.remove(0).run(); assertFalse(pending.isDone()); assertEquals(List.of(1000L), delays);
            writer.put("schwab", version(4)); assertTrue(jobs.isEmpty()); timers.remove(0).run(); jobs.remove(0).run();
            pending.get(1, TimeUnit.SECONDS); assertEquals(List.of(1, 4), attempted); assertEquals(1, failures.get());
            assertEquals(3, writer.coalesced());
        }
    }
    @Test void shutdownFlushesDirtyStateWithoutDependingOnAnotherTimer() {
        List<Runnable> jobs = new ArrayList<>(), timers = new ArrayList<>(); AtomicInteger attempts = new AtomicInteger();
        var writer = new CoalescingStateWriter(jobs::add, (delay, task) -> { timers.add(task); return () -> timers.remove(task); },
                (profile, state) -> { if (attempts.incrementAndGet() == 1) throw new java.io.IOException(); }, error -> {});
        writer.put("schwab", version(1)); jobs.remove(0).run(); assertEquals(1, timers.size());
        writer.close(); assertTrue(timers.isEmpty()); assertEquals(1, jobs.size()); jobs.remove(0).run();
        assertTrue(writer.pending().isDone()); assertFalse(writer.pending().isCompletedExceptionally()); assertEquals(2, attempts.get());
    }
    @Test void failedShutdownWriteCompletesExceptionallyInsteadOfLeakingWorkers() {
        List<Runnable> jobs = new ArrayList<>();
        var writer = new CoalescingStateWriter(jobs::add, (delay, task) -> () -> {}, (profile, state) -> { throw new java.io.IOException(); }, error -> {});
        writer.put("schwab", version(1)); writer.close(); jobs.remove(0).run();
        assertTrue(writer.pending().isCompletedExceptionally());
    }
    @Test void delayedDisplayKeepsOnlyLatestProjectionAndRetriesExecutorOverload() {
        List<Runnable> jobs = new ArrayList<>(); List<Integer> rendered = new ArrayList<>();
        AtomicBoolean overloaded = new AtomicBoolean(true); AtomicInteger failures = new AtomicInteger();
        try (var display = new LatestTasks(task -> { if (overloaded.get()) throw new RejectedExecutionException(); jobs.add(task); }, error -> failures.incrementAndGet())) {
            for (int i = 0; i < 1000; i++) { int value = i; display.put("AAPL", () -> rendered.add(value)); }
            assertTrue(jobs.isEmpty()); overloaded.set(false); display.retry(); assertEquals(1, jobs.size());
            jobs.remove(0).run(); assertEquals(List.of(999), rendered); assertEquals(999, display.coalesced());
            assertEquals(1, failures.get(), "Report overload once until the scheduled retry");
        }
    }
    @Test void saturatedHistoryWorkersCannotStarveBrokerOrPersistenceCapacity() throws Exception {
        try (RuntimeWork work = RuntimeWork.production()) {
            CountDownLatch entered = new CountDownLatch(4), release = new CountDownLatch(1);
            try {
                for (int i = 0; i < 4; i++) work.background.execute(() -> {
                    entered.countDown(); try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                });
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                CompletableFuture<Void> broker = CompletableFuture.runAsync(() -> {}, work.broker);
                CompletableFuture<Void> persistence = CompletableFuture.runAsync(() -> {}, work.persistence);
                CompletableFuture.allOf(broker, persistence).get(1, TimeUnit.SECONDS);
                CountDownLatch parallelReads = new CountDownLatch(2);
                List<CompletableFuture<Void>> reads = new ArrayList<>();
                for (int i = 0; i < 2; i++) reads.add(CompletableFuture.runAsync(() -> {
                    parallelReads.countDown(); try { assertTrue(parallelReads.await(1, TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { throw new AssertionError(error); }
                }, work.accountRequests));
                CompletableFuture.allOf(reads.toArray(new CompletableFuture<?>[0])).get(2, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
    }
}
