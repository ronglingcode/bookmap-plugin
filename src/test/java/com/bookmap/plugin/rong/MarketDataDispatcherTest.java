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
}
