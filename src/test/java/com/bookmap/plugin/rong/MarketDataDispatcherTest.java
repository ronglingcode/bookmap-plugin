package com.bookmap.plugin.rong;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketDataDispatcherTest {
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(3, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    @Test void producersReturnWhileWorkerIsBusyAndCallbacksKeepOrderOffTheCallerThread() {
        CountDownLatch busy = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        List<Integer> observed = new ArrayList<>();
        AtomicReference<Thread> processingThread = new AtomicReference<>();
        Thread producer = Thread.currentThread();
        try (MarketDataDispatcher dispatcher = new MarketDataDispatcher("TEST", message -> fail(message))) {
            dispatcher.submit(() -> { busy.countDown(); await(release); });
            await(busy);
            for (int i = 0; i < 200; i++) {
                int value = i;
                assertTrue(dispatcher.submit(() -> { processingThread.set(Thread.currentThread()); observed.add(value); }));
            }
            assertTrue(observed.isEmpty());
            dispatcher.submit(done::countDown);
            release.countDown(); await(done);
            assertNotSame(producer, processingThread.get());
            assertTrue(processingThread.get().getName().startsWith("bmtrader-market-"));
            for (int i = 0; i < 200; i++) assertEquals(i, observed.get(i));
        } finally { release.countDown(); }
    }

    @Test void backlogTemporarilyDisablesLiquidityAndRecoversWhenCaughtUp() {
        AtomicLong clock = new AtomicLong();
        CountDownLatch busy = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        try (MarketDataDispatcher dispatcher = new MarketDataDispatcher("TEST", 8, clock::get, message -> fail(message))) {
            dispatcher.submit(() -> { busy.countDown(); await(release); }); await(busy);
            assertTrue(dispatcher.healthy());
            clock.set(250_000_001);
            assertFalse(dispatcher.healthy());
            dispatcher.submit(done::countDown); release.countDown(); await(done);
            assertTrue(dispatcher.healthy());
        } finally { release.countDown(); }
    }

    @Test void overflowAndProcessingExceptionsFailClosedWithoutBlockingProducers() {
        CountDownLatch busy = new CountDownLatch(1), release = new CountDownLatch(1), warning = new CountDownLatch(1);
        AtomicReference<String> reason = new AtomicReference<>();
        try (MarketDataDispatcher dispatcher = new MarketDataDispatcher("TEST", 1, System::nanoTime,
                message -> { reason.set(message); warning.countDown(); })) {
            dispatcher.submit(() -> { busy.countDown(); await(release); }); await(busy);
            assertTrue(dispatcher.submit(() -> fail("Overflow must discard incomplete evidence")));
            assertFalse(dispatcher.submit(() -> {}));
            assertFalse(dispatcher.healthy());
            assertFalse(dispatcher.submit(() -> {}));
            await(warning); assertTrue(reason.get().contains("overflow"));
        } finally { release.countDown(); }
        CountDownLatch failed = new CountDownLatch(1);
        try (MarketDataDispatcher dispatcher = new MarketDataDispatcher("TEST", message -> failed.countDown())) {
            dispatcher.submit(() -> { throw new IllegalStateException("probe"); });
            await(failed); assertFalse(dispatcher.healthy());
        }
    }

    @Test void shutdownRejectsCallbacksAndDoesNotRunQueuedWork() {
        CountDownLatch busy = new CountDownLatch(1), release = new CountDownLatch(1);
        MarketDataDispatcher dispatcher = new MarketDataDispatcher("TEST", message -> fail(message));
        try {
            dispatcher.submit(() -> { busy.countDown(); await(release); }); await(busy);
            dispatcher.submit(() -> fail("Detached callback ran"));
            dispatcher.close();
            assertFalse(dispatcher.healthy()); assertFalse(dispatcher.submit(() -> {}));
        } finally { release.countDown(); dispatcher.close(); }
    }
}
