package com.bookmap.plugin.rong;

import com.google.gson.JsonObject;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.HashMap;
import java.util.Map;

/** Synchronous, ordered state reduction. There is no raw-event queue or worker. */
public final class MarketDataDispatcher implements AutoCloseable {
    private final Consumer<String> notice;
    private final LongSupplier clock;
    private volatile boolean closed, failed;
    private long processed, maxProcessingNs;
    private long failures, optionalFailures;
    private final Map<String, Long> retryAfter = new HashMap<>();
    private String slowestCallback = "", failureReason = "";

    public MarketDataDispatcher(String symbol, Consumer<String> notice) {
        this(System::nanoTime, notice);
    }
    MarketDataDispatcher(LongSupplier clock, Consumer<String> notice) {
        this.clock = clock; this.notice = notice;
    }
    public boolean submit(Runnable task) { return submit("callback", task); }
    public synchronized boolean submit(String kind, Runnable task) {
        if (closed) return false;
        long started = clock.getAsLong();
        try { task.run(); processed++; return true; }
        catch (RuntimeException error) {
            fail(kind, error); return false;
        } finally {
            long elapsed = Math.max(0, clock.getAsLong() - started);
            if (elapsed > maxProcessingNs) { maxProcessingNs = elapsed; slowestCallback = kind; }
        }
    }
    /** Independent core consumers must not prevent each other's updates. */
    public synchronized void reduce(String component, Runnable task) {
        if (closed) return;
        try { task.run(); } catch (RuntimeException error) { fail(component, error); }
    }
    /** Stateless publications/projections retry after a cooldown without poisoning market evidence. */
    public synchronized void optional(String component, Runnable task) {
        if (closed || clock.getAsLong() < retryAfter.getOrDefault(component, Long.MIN_VALUE)) return;
        try { task.run(); retryAfter.remove(component); }
        catch (RuntimeException error) {
            optionalFailures++; retryAfter.put(component, clock.getAsLong() + 1_000_000_000L);
            report("Observer " + component + " failed (" + error.getClass().getSimpleName() + "); retrying after cooldown");
        }
    }
    private void fail(String component, RuntimeException error) {
        failed = true; failures++;
        failureReason = component + " processing failed (" + error.getClass().getSimpleName() + "); counters incomplete";
        if (failures == 1 || (failures & (failures - 1)) == 0) report("ERROR: Market data " + failureReason);
    }
    private void report(String message) { try { notice.accept(message); } catch (RuntimeException ignored) { /* logging cannot stop ingestion */ } }
    public boolean healthy() { return !closed && !failed; }
    public synchronized JsonObject status() {
        JsonObject value = new JsonObject();
        value.addProperty("marketDataState", closed ? "stopped" : failed ? "failed" : "current");
        value.addProperty("healthy", healthy()); value.addProperty("queueDepth", 0);
        value.addProperty("lagMillis", 0); value.addProperty("processedEvents", processed);
        value.addProperty("maxProcessingMicros", maxProcessingNs / 1000L);
        value.addProperty("slowestCallback", slowestCallback); value.addProperty("marketDataError", failureReason);
        value.addProperty("processingFailures", failures); value.addProperty("observerFailures", optionalFailures);
        return value;
    }
    @Override public void close() { closed = true; }
}
