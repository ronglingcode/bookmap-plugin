package com.bookmap.plugin.rong.miniviteapp.runtime;

import java.util.*;
import java.util.concurrent.*;

/** Separate capacity for vendor reads, background history, persistence and projections. */
public final class RuntimeWork implements AutoCloseable {
    public final Executor background, broker, persistence, display, accountRequests;
    private final List<ExecutorService> owned;
    private RuntimeWork(Executor background, Executor broker, Executor persistence, Executor display, Executor accountRequests,
            List<ExecutorService> owned) {
        this.background = background; this.broker = broker; this.persistence = persistence;
        this.display = display; this.accountRequests = accountRequests; this.owned = owned;
    }
    public static RuntimeWork using(Executor executor) {
        // A caller-supplied executor may be a single-thread event loop. Never join its own queued reads.
        return new RuntimeWork(executor, executor, executor, executor, Runnable::run, Collections.emptyList());
    }
    public static RuntimeWork production() {
        List<ExecutorService> pools = new ArrayList<>();
        return new RuntimeWork(pool(pools, "background", 4, 64), pool(pools, "broker", 2, 16),
                pool(pools, "persistence", 1, 4), pool(pools, "display", 1, 16), pool(pools, "account-read", 2, 4), pools);
    }
    private static ExecutorService pool(List<ExecutorService> pools, String name, int threads, int capacity) {
        ExecutorService pool = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), task -> {
                    Thread thread = new Thread(task, "bmtrader-" + name); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        pools.add(pool); return pool;
    }
    @Override public void close() { owned.forEach(ExecutorService::shutdownNow); }
}
