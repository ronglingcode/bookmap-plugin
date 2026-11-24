package com.bookmap.plugin.rong.miniviteapp.runtime;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Only replaceable projections belong here, never trades, fills or order commands. */
public final class LatestTasks implements AutoCloseable {
    private final Executor executor;
    private final Consumer<RuntimeException> failure;
    private final Map<String, Runnable> pending = new LinkedHashMap<>();
    private boolean running, closed, rejected;
    private long coalesced;
    public LatestTasks(Executor executor, Consumer<RuntimeException> failure) { this.executor = executor; this.failure = failure; }
    public void put(String key, Runnable task) {
        synchronized (this) {
            if (closed) return;
            if (!pending.containsKey(key) && pending.size() >= 64) {
                pending.remove(pending.keySet().iterator().next()); coalesced++;
            }
            if (pending.put(key, task) != null) coalesced++;
        }
        start(false);
    }
    public void retry() { start(true); }
    private void start(boolean allowRetry) {
        synchronized (this) { if (closed || running || pending.isEmpty() || rejected && !allowRetry) return; running = true; rejected = false; }
        try { executor.execute(this::drain); }
        catch (RejectedExecutionException error) { synchronized (this) { running = false; rejected = true; } failure.accept(error); }
    }
    private void drain() {
        Map<String, Runnable> batch;
        synchronized (this) { batch = new LinkedHashMap<>(pending); pending.clear(); }
        try {
            for (Runnable task : batch.values()) {
                synchronized (this) { if (closed) return; }
                try { task.run(); } catch (RuntimeException error) { failure.accept(error); }
            }
        } finally { synchronized (this) { running = false; } start(false); }
    }
    public synchronized long coalesced() { return coalesced; }
    @Override public synchronized void close() { closed = true; pending.clear(); }
}
