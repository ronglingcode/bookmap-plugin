package com.bookmap.plugin.rong;

import com.google.gson.JsonObject;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Synchronous, ordered state reduction. There is no raw-event queue or worker. */
public final class MarketDataDispatcher implements AutoCloseable {
    private final Consumer<String> notice;
    private final LongSupplier clock;
    private volatile boolean closed, failed;
    private long processed, maxProcessingNs;
    private String slowestCallback = "", failureReason = "";

    public MarketDataDispatcher(String symbol, Consumer<String> notice) {
        this(System::nanoTime, notice);
    }
    MarketDataDispatcher(LongSupplier clock, Consumer<String> notice) {
        this.clock = clock; this.notice = notice;
    }
    public boolean submit(Runnable task) { return submit("callback", task); }
    public synchronized boolean submit(String kind, Runnable task) {
        if (closed || failed) return false;
        long started = clock.getAsLong();
        try { task.run(); processed++; return true; }
        catch (RuntimeException error) {
            failed = true; failureReason = "processing failed (" + error.getClass().getSimpleName() + "); counters incomplete";
            notice.accept("ERROR: Market data " + failureReason); return false;
        } finally {
            long elapsed = Math.max(0, clock.getAsLong() - started);
            if (elapsed > maxProcessingNs) { maxProcessingNs = elapsed; slowestCallback = kind; }
        }
    }
    public boolean healthy() { return !closed && !failed; }
    public synchronized JsonObject status() {
        JsonObject value = new JsonObject();
        value.addProperty("marketDataState", closed ? "stopped" : failed ? "failed" : "current");
        value.addProperty("healthy", healthy()); value.addProperty("queueDepth", 0);
        value.addProperty("lagMillis", 0); value.addProperty("processedEvents", processed);
        value.addProperty("maxProcessingMicros", maxProcessingNs / 1000L);
        value.addProperty("slowestCallback", slowestCallback); value.addProperty("marketDataError", failureReason);
        return value;
    }
    @Override public void close() { closed = true; }
}
