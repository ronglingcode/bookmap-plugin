package com.bookmap.plugin.rong;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Ordered, bounded callback handoff. Producers never wait for processing or UI work. */
public final class MarketDataDispatcher implements AutoCloseable {
    private static final long MAX_LAG_NS = 250_000_000L;
    private final ArrayBlockingQueue<Work> queue;
    private final AtomicBoolean failed = new AtomicBoolean();
    private final Consumer<String> failure;
    private final LongSupplier clock;
    private final Thread worker;
    private volatile boolean closed;
    private volatile Work current;

    private static final class Work {
        final Runnable task;
        final long enqueued;
        Work(Runnable task, long enqueued) { this.task = task; this.enqueued = enqueued; }
    }

    public MarketDataDispatcher(String symbol, Consumer<String> failure) {
        this(symbol, 32768, System::nanoTime, failure);
    }

    MarketDataDispatcher(String symbol, int capacity, LongSupplier clock, Consumer<String> failure) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.clock = clock;
        this.failure = failure;
        worker = new Thread(this::run, "bmtrader-market-" + symbol);
        worker.setDaemon(true);
        worker.start();
    }

    public boolean submit(Runnable task) {
        if (closed || failed.get()) return false;
        if (queue.offer(new Work(task, clock.getAsLong()))) return true;
        fail("market-data queue overflow; disable and re-enable the addon to rebuild liquidity state");
        return false;
    }

    // RongPlugin retains its package while this class is relocated by obfuscation.
    // Method-reference linkage requires public access across that package boundary.
    public boolean healthy() {
        if (closed || failed.get()) return false;
        Work active = current, next = queue.peek();
        long now = clock.getAsLong();
        return (active == null || now - active.enqueued <= MAX_LAG_NS)
                && (next == null || now - next.enqueued <= MAX_LAG_NS);
    }

    private void run() {
        try {
            while (!closed && !failed.get()) {
                Work work = queue.take();
                current = work;
                try {
                    if (!closed && !failed.get()) work.task.run();
                } finally { current = null; }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            fail("market-data processing failed (" + error.getClass().getSimpleName()
                    + "); disable and re-enable the addon to rebuild liquidity state");
        } finally { queue.clear(); }
    }

    private void fail(String reason) {
        if (failed.compareAndSet(false, true)) {
            queue.clear();
            worker.interrupt();
            // In particular, overflow reporting must not run on Bookmap's producer thread.
            Thread reporter = new Thread(() -> failure.accept(reason), "bmtrader-market-warning");
            reporter.setDaemon(true);
            reporter.start();
        }
    }

    @Override public void close() {
        closed = true;
        queue.clear();
        worker.interrupt();
    }
}
