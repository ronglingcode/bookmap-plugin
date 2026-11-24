package com.bookmap.plugin.rong.miniviteapp.runtime;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Bounded, asynchronous command lanes. No worker waits for a mutation future. */
public final class SymbolCommandQueue implements AutoCloseable {
    private static final int MAX_SYMBOLS = 16, MAX_PENDING = 8;
    private static final class Command {
        final int priority;
        final Supplier<CompletionStage<Void>> run;
        final CompletableFuture<Void> result = new CompletableFuture<>();
        Command(int priority, Supplier<CompletionStage<Void>> run) { this.priority = priority; this.run = run; }
    }
    private static final class Lane {
        final List<Command> pending = new ArrayList<>();
        boolean active;
        Command running;
    }
    private final Map<String, Lane> lanes = new HashMap<>();
    private final ExecutorService workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_SYMBOLS), task -> {
                Thread thread = new Thread(task, "bmtrader-command"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private boolean closed;

    /** 0: cancel/flatten, 1: management, 2: opening. Urgent commands discard queued entries. */
    public synchronized CompletableFuture<Void> submit(String symbol, int priority, Supplier<CompletionStage<Void>> run) {
        Command command = new Command(priority, run);
        if (closed || !lanes.containsKey(symbol) && lanes.size() >= MAX_SYMBOLS) {
            command.result.completeExceptionally(new RejectedExecutionException("Command capacity unavailable")); return command.result;
        }
        Lane lane = lanes.computeIfAbsent(symbol, key -> new Lane());
        if (priority == 0) {
            Iterator<Command> pending = lane.pending.iterator();
            while (pending.hasNext()) {
                Command previous = pending.next();
                if (previous.priority == 2) {
                    pending.remove(); previous.result.completeExceptionally(new RejectedExecutionException("Entry superseded by cancel/flatten"));
                }
            }
        }
        if (lane.pending.size() >= MAX_PENDING) {
            // Preserve room for a risk-reducing command by evicting a lower-priority queued action.
            Command last = lane.pending.get(lane.pending.size() - 1);
            if (priority < last.priority) {
                lane.pending.remove(last); last.result.completeExceptionally(new RejectedExecutionException("Command superseded by risk management"));
            } else {
                command.result.completeExceptionally(new RejectedExecutionException("Symbol command queue full")); return command.result;
            }
        }
        lane.pending.add(command); lane.pending.sort(Comparator.comparingInt(value -> value.priority));
        if (!lane.active) advance(symbol, lane);
        return command.result;
    }
    private synchronized void advance(String symbol, Lane lane) {
        if (closed || lane.pending.isEmpty()) { lanes.remove(symbol, lane); lane.active = false; return; }
        Command command = lane.pending.remove(0); lane.active = true; lane.running = command;
        try {
            workers.execute(() -> {
                try {
                    command.run.get().whenComplete((value, error) -> finish(symbol, lane, command, error));
                } catch (Throwable error) { finish(symbol, lane, command, error); }
            });
        } catch (RejectedExecutionException error) { finish(symbol, lane, command, error); }
    }
    private void finish(String symbol, Lane lane, Command command, Throwable error) {
        if (error == null) command.result.complete(null); else command.result.completeExceptionally(error);
        advance(symbol, lane);
    }
    public synchronized int pendingCount() { return lanes.values().stream().mapToInt(lane -> lane.pending.size()).sum(); }
    public synchronized CompletableFuture<Void> idle() {
        List<CompletableFuture<Void>> results = new ArrayList<>();
        for (Lane lane : lanes.values()) {
            if (lane.running != null) results.add(lane.running.result.handle((value, error) -> null));
            for (Command command : lane.pending) results.add(command.result.handle((value, error) -> null));
        }
        return CompletableFuture.allOf(results.toArray(new CompletableFuture<?>[0]));
    }
    @Override public synchronized void close() {
        closed = true;
        for (Lane lane : new ArrayList<>(lanes.values())) {
            if (lane.running != null) lane.running.result.completeExceptionally(new RejectedExecutionException("Native executor stopped"));
            for (Command command : lane.pending)
                command.result.completeExceptionally(new RejectedExecutionException("Native executor stopped"));
        }
        lanes.clear(); workers.shutdownNow();
    }
}
