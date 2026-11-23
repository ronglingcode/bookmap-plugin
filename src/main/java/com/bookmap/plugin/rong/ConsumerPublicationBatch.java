package com.bookmap.plugin.rong;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Lossless ordered publications, serialized outside the market-event state lock. */
final class ConsumerPublicationBatch {
    static final long INTERVAL_MS = 200;
    private List<Supplier<String>> pending = new ArrayList<>();
    private final Consumer<String> send;
    private final Object flushLock = new Object();
    private long lastFlush = Long.MIN_VALUE;
    ConsumerPublicationBatch(Consumer<String> send) { this.send = send; }
    synchronized void add(Supplier<String> event) { pending.add(event); }
    synchronized void add(String event) { pending.add(() -> event); }
    void finish() {
        long delay;
        synchronized (this) {
            if (pending.isEmpty()) return;
            delay = lastFlush == Long.MIN_VALUE ? 0 : Math.max(0, INTERVAL_MS - (System.nanoTime() / 1_000_000L - lastFlush));
        }
        if (delay > 0) try { Thread.sleep(delay); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("Final publication interrupted", error); }
        flush(System.nanoTime() / 1_000_000L);
    }
    void flush(long monotonicMillis) { synchronized (flushLock) { flushOrdered(monotonicMillis); } }
    private void flushOrdered(long monotonicMillis) {
        List<Supplier<String>> batch;
        synchronized (this) {
            if (pending.isEmpty() || lastFlush != Long.MIN_VALUE && monotonicMillis - lastFlush < INTERVAL_MS) return;
            lastFlush = monotonicMillis; batch = pending; pending = new ArrayList<>();
        }
        try {
            StringBuilder wire = new StringBuilder("{\"type\":\"cairo_batch\",\"version\":1,\"events\":[");
            for (int i = 0; i < batch.size(); i++) { if (i > 0) wire.append(','); wire.append(batch.get(i).get()); }
            send.accept(wire.append("]}").toString());
        } catch (RuntimeException failure) {
            synchronized (this) { batch.addAll(pending); pending = batch; }
            throw failure;
        }
    }
}
