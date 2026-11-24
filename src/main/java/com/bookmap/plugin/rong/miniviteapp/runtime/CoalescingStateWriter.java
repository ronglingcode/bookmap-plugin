package com.bookmap.plugin.rong.miniviteapp.runtime;

import com.bookmap.plugin.rong.miniviteapp.ports.SocketPort;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Latest snapshot per profile, one write at a time, with bounded exponential retry. */
public final class CoalescingStateWriter implements AutoCloseable {
    @FunctionalInterface public interface Write { void save(String profile, JsonObject state) throws Exception; }
    private final Executor executor;
    private final SocketPort.Scheduler scheduler;
    private final Write write;
    private final Consumer<Exception> failure;
    private final Map<String, JsonObject> pending = new LinkedHashMap<>();
    private CompletableFuture<Void> completion = CompletableFuture.completedFuture(null);
    private Runnable cancelRetry;
    private boolean busy, closing;
    private int failures;
    private long coalesced;
    public CoalescingStateWriter(Executor executor, SocketPort.Scheduler scheduler, Write write, Consumer<Exception> failure) {
        this.executor = executor; this.scheduler = scheduler; this.write = write; this.failure = failure;
    }
    public void put(String profile, JsonObject state) {
        synchronized (this) {
            if (closing) return;
            if (completion.isDone()) completion = new CompletableFuture<>();
            if (pending.put(profile, state.deepCopy()) != null) coalesced++;
        }
        dispatch();
    }
    private void dispatch() {
        String profile; JsonObject snapshot;
        synchronized (this) {
            if (busy || cancelRetry != null || pending.isEmpty()) return;
            var first = pending.entrySet().iterator().next(); profile = first.getKey(); snapshot = first.getValue(); busy = true;
        }
        try {
            executor.execute(() -> {
                Exception error = null;
                try { write.save(profile, snapshot); } catch (Exception failed) { error = failed; }
                finished(profile, snapshot, error);
            });
        } catch (RejectedExecutionException error) { finished(profile, snapshot, error); }
    }
    private void finished(String profile, JsonObject snapshot, Exception error) {
        synchronized (this) {
            busy = false;
            if (error == null) {
                if (pending.get(profile) == snapshot) pending.remove(profile);
                failures = 0;
                if (pending.isEmpty()) completion.complete(null);
            } else {
                failures++;
                if (closing) completion.completeExceptionally(error);
                else {
                    long delay = Math.min(30000L, 1000L << Math.min(5, failures - 1));
                    try {
                        cancelRetry = scheduler.after(delay, () -> {
                            synchronized (CoalescingStateWriter.this) { cancelRetry = null; }
                            dispatch();
                        });
                    } catch (RuntimeException schedulingFailure) { completion.completeExceptionally(schedulingFailure); }
                }
            }
        }
        if (error != null) { failure.accept(error); return; }
        dispatch();
    }
    public synchronized CompletableFuture<Void> pending() { return completion; }
    public synchronized long coalesced() { return coalesced; }
    @Override public void close() {
        synchronized (this) {
            closing = true; if (!pending.isEmpty() && completion.isDone()) completion = new CompletableFuture<>();
            if (cancelRetry != null) { cancelRetry.run(); cancelRetry = null; }
        }
        dispatch();
    }
}
