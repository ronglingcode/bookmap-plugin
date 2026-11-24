package com.bookmap.plugin.rong;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketDataDispatcherTest {
    @Test void everyEventIsAppliedBeforeSubmitReturnsWithNoQueueOrThreadHandoff() {
        AtomicLong total = new AtomicLong(); Thread caller = Thread.currentThread();
        try (var reducer = new MarketDataDispatcher("TEST", message -> fail(message))) {
            for (int i = 0; i < 100_000; i++) {
                assertTrue(reducer.submit("trade", () -> { assertSame(caller, Thread.currentThread()); total.incrementAndGet(); }));
                assertEquals(i + 1, total.get());
            }
            assertEquals(100_000, reducer.status().get("processedEvents").getAsLong());
            assertEquals(0, reducer.status().get("queueDepth").getAsInt()); assertTrue(reducer.healthy());
        }
    }
    @Test void concurrentProducersCannotOverlapStateMutation() throws Exception {
        AtomicInteger active = new AtomicInteger(), total = new AtomicInteger();
        try (var reducer = new MarketDataDispatcher("TEST", message -> fail(message))) {
            CompletableFuture<?>[] producers = new CompletableFuture<?>[4];
            for (int i = 0; i < 4; i++) producers[i] = CompletableFuture.runAsync(() -> {
                for (int j = 0; j < 10_000; j++) reducer.submit(() -> {
                    assertEquals(1, active.incrementAndGet()); total.incrementAndGet(); active.decrementAndGet();
                });
            });
            CompletableFuture.allOf(producers).get(10, TimeUnit.SECONDS); assertEquals(40_000, total.get());
        }
    }
    @Test void processingErrorsRemainVisibleAndDetachmentRejectsFurtherEvents() {
        AtomicReference<String> error = new AtomicReference<>();
        var reducer = new MarketDataDispatcher("TEST", error::set);
        reducer.submit(() -> { throw new IllegalStateException(); });
        assertFalse(reducer.healthy()); assertEquals("failed", reducer.status().get("marketDataState").getAsString());
        assertTrue(error.get().contains("IllegalStateException")); reducer.close();
        assertFalse(reducer.submit(() -> fail("detached callback")));
        assertEquals("stopped", reducer.status().get("marketDataState").getAsString());
    }
    @Test void failedCoreConsumerDoesNotStopOtherConsumersOrFutureEvents() {
        AtomicInteger total = new AtomicInteger();
        try (var reducer = new MarketDataDispatcher("TEST", message -> { throw new IllegalStateException("Log unavailable"); })) {
            assertTrue(reducer.submit(() -> {
                reducer.reduce("liquidity", () -> { throw new IllegalArgumentException(); });
                reducer.reduce("price", total::incrementAndGet);
            }));
            assertTrue(reducer.submit(total::incrementAndGet));
            assertEquals(2, total.get()); assertFalse(reducer.healthy());
            assertEquals(1, reducer.status().get("processingFailures").getAsLong());
        }
    }
    @Test void optionalDisplayFailureRetriesAfterCooldownWithoutInvalidatingEvidence() {
        AtomicLong time = new AtomicLong(); AtomicInteger attempts = new AtomicInteger();
        try (var reducer = new MarketDataDispatcher(time::get, message -> {})) {
            reducer.optional("display", () -> { attempts.incrementAndGet(); throw new IllegalStateException(); });
            reducer.optional("display", attempts::incrementAndGet); assertEquals(1, attempts.get());
            time.set(1_000_000_000L); reducer.optional("display", attempts::incrementAndGet);
            assertEquals(2, attempts.get()); assertTrue(reducer.healthy());
            assertEquals(1, reducer.status().get("observerFailures").getAsLong());
        }
    }
}
