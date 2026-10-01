package com.bookmap.plugin.rong.miniviteapp.runtime;

import com.bookmap.plugin.rong.miniviteapp.ports.SocketPort;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Mirrors TS ManagedSocket. Never hold the lifecycle monitor while invoking consumers. */
public final class ManagedSocket implements AutoCloseable {
    public interface Setup { String url(); void opened(SocketPort.Connection socket); void message(SocketPort.Connection socket, String data); }
    private final SocketPort sockets;
    private final SocketPort.Scheduler scheduler;
    private final Executor executor;
    private final Callable<Setup> setup;
    private final Consumer<String> status;
    private Runnable cancelRetry, dispose;
    private volatile boolean stopped = true;
    private boolean closed;
    private boolean retryPending;
    private int attempts;
    public ManagedSocket(SocketPort sockets, SocketPort.Scheduler scheduler, Executor executor, Callable<Setup> setup, Consumer<String> status) {
        this.sockets = sockets; this.scheduler = scheduler; this.executor = executor; this.setup = setup; this.status = status;
    }
    public void start() { synchronized (this) { if (closed || !stopped) return; stopped = false; } connect(); }
    public void ready() { synchronized (this) { attempts = 0; } status.accept("connected"); }
    public void reconnect() { Runnable stop; synchronized (this) { stop = dispose; } if (stop != null) stop.run(); retry(); }
    private void retry() {
        long delay;
        synchronized (this) {
            if (stopped || retryPending) return; retryPending = true;
            delay = Math.min(30000, 1000L << Math.min(attempts++, 5));
        }
        status.accept("reconnecting in " + delay + "ms");
        Runnable cancel = scheduler.after(delay, () -> {
            synchronized (this) { cancelRetry = null; retryPending = false; if (stopped) return; }
            connect();
        });
        synchronized (this) { if (stopped || !retryPending) cancel.run(); else cancelRetry = cancel; }
    }
    private void connect() {
        try { executor.execute(() -> {
            try {
                if (stopped) return;
                Setup config = setup.call(); if (stopped) return;
                AtomicBoolean active = new AtomicBoolean(true); AtomicReference<SocketPort.Connection> current = new AtomicReference<>();
                Runnable disposeConnection = () -> { active.set(false); SocketPort.Connection socket = current.get(); if (socket != null) socket.close(); };
                Runnable failed = () -> { if (active.getAndSet(false)) { SocketPort.Connection socket = current.get(); if (socket != null) socket.close(); retry(); } };
                SocketPort.Handlers handlers = new SocketPort.Handlers() {
                    public void opened(SocketPort.Connection socket) { current.set(socket); if (!active.get() || stopped) { socket.close(); return; } try { config.opened(socket); } catch (RuntimeException error) { failed.run(); } }
                    public void message(SocketPort.Connection socket, String data) { if (active.get() && !stopped) { try { config.message(socket, data); } catch (RuntimeException error) { status.accept("invalid stream message"); failed.run(); } } }
                    public void closed() { if (active.getAndSet(false)) retry(); }
                    public void failed() { failed.run(); }
                };
                synchronized (this) { if (stopped) return; dispose = disposeConnection; }
                SocketPort.Connection socket = sockets.open(config.url(), handlers); current.set(socket);
                if (!active.get() || stopped) socket.close();
            } catch (Exception error) { status.accept("connection setup failed"); retry(); }
        }); } catch (RuntimeException error) { status.accept("connection setup failed"); retry(); }
    }
    @Override public void close() {
        Runnable cancel, stop;
        synchronized (this) { closed = true; stopped = true; cancel = cancelRetry; cancelRetry = null; retryPending = false; stop = dispose; }
        if (cancel != null) cancel.run(); if (stop != null) stop.run();
    }
}
