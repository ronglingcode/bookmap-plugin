package com.bookmap.plugin.rong;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Coalesces stable per-chart redraw tasks; caller threads never build images or shapes. */
public final class BackgroundChartRenderer implements AutoCloseable {
    private final Set<Runnable> pending = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService worker;
    private volatile boolean closed;

    public BackgroundChartRenderer(String name) {
        this(Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, name); thread.setDaemon(true); return thread;
        }));
    }

    public BackgroundChartRenderer(ScheduledExecutorService worker) {
        this.worker = worker;
        worker.scheduleWithFixedDelay(this::drain, 200, 200, TimeUnit.MILLISECONDS);
    }

    public void request(Runnable redraw) {
        if (!closed) pending.add(redraw);
    }

    public void forget(Runnable redraw) { pending.remove(redraw); }

    private void drain() {
        for (Runnable redraw : new ArrayList<>(pending)) {
            if (closed) return;
            if (!pending.remove(redraw)) continue;
            try { redraw.run(); }
            catch (RuntimeException disposingCanvas) {
                // Preserve later requests and keep one bad/disposed canvas from killing the timer.
                if (!closed) pending.add(redraw);
            }
        }
    }

    @Override public void close() {
        closed = true;
        pending.clear();
        worker.shutdownNow();
    }
}
