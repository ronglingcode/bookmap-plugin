package com.bookmap.plugin.rong.miniviteapp.runtime;

import java.util.concurrent.*;

/** Coalesces concurrent reads without holding a monitor during I/O. */
public final class FreshRead<T> {
    private CompletableFuture<T> active;
    private long started;
    public T read(long notBeforeNanos, Callable<T> request) throws Exception {
        while (true) {
            CompletableFuture<T> pending; boolean owner; long readStarted;
            synchronized (this) {
                owner = active == null;
                if (owner) { active = new CompletableFuture<>(); started = System.nanoTime(); }
                pending = active; readStarted = started;
            }
            if (owner) {
                try { pending.complete(request.call()); }
                catch (Throwable error) { pending.completeExceptionally(error); }
                finally { synchronized (this) { if (active == pending) active = null; } }
            }
            try {
                T value = pending.get();
                if (readStarted >= notBeforeNanos) return value;
            } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new IllegalStateException(cause);
            }
        }
    }
}
