package com.bookmap.plugin.rong.miniviteapp.runtime;

import com.bookmap.plugin.rong.miniviteapp.adapters.JdkHttp;
import com.bookmap.plugin.rong.miniviteapp.adapters.JdkSockets;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.concurrent.*;

/** Owns native transports and daemon workers; no plugin API or websocket display dependency. */
public final class NativeRuntime implements AutoCloseable {
    private final ExecutorService io = Executors.newFixedThreadPool(6, task -> daemon(task, "bmtrader-services"));
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "bmtrader-clock"));
    public final TradingRuntime trading;
    private static Thread daemon(Runnable task, String name) { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; }
    public NativeRuntime(TradingRuntime.Events events) throws IOException {
        try {
            LocalCredentials credentials = new LocalCredentials(); JsonObject sections = new JsonObject();
            for (String key : new String[]{"massive", "firebaseConfig", "tradingPolicy"}) sections.add(key, credentials.section(key));
            trading = new TradingRuntime(new JdkHttp(), credentials, sections, new JdkSockets(), (delay, task) -> {
                ScheduledFuture<?> scheduled = clock.schedule(task, delay, TimeUnit.MILLISECONDS); return () -> scheduled.cancel(false);
            }, io, System::currentTimeMillis, events);
        } catch (IOException | RuntimeException error) { io.shutdownNow(); clock.shutdownNow(); throw error; }
    }
    public void start() { trading.start().exceptionally(error -> null); }
    @Override public void close() { trading.close(); clock.shutdownNow(); trading.pendingPersistence().whenComplete((value, error) -> io.shutdown()); }
}
