package com.bookmap.plugin.rong.miniviteapp;

import java.util.function.BiConsumer;

/** Logging destinations are explicit; standalone consumers still receive every full message. */
@FunctionalInterface
public interface ExecutionLog extends BiConsumer<String, String> {
    default void detail(String symbol, String message) { accept(symbol, message); }
    default void summary(String symbol, String message, String screenMessage) { accept(symbol, message); }
    default void transition(String symbol, String message) { accept(symbol, message); }
    default void aggregate(String symbol, String group, String message, String screenMessage) { summary(symbol, message, screenMessage); }

    static ExecutionLog from(BiConsumer<String, String> log) {
        return log instanceof ExecutionLog ? (ExecutionLog) log : log::accept;
    }
    static ExecutionLog isolated(BiConsumer<String, String> destination, java.util.function.Consumer<RuntimeException> failed) {
        ExecutionLog log = from(destination);
        return new ExecutionLog() {
            private void safely(Runnable write) { try { write.run(); } catch (RuntimeException error) { failed.accept(error); } }
            public void accept(String symbol, String message) { safely(() -> log.accept(symbol, message)); }
            public void detail(String symbol, String message) { safely(() -> log.detail(symbol, message)); }
            public void summary(String symbol, String message, String screen) { safely(() -> log.summary(symbol, message, screen)); }
            public void transition(String symbol, String message) { safely(() -> log.transition(symbol, message)); }
            public void aggregate(String symbol, String group, String message, String screen) { safely(() -> log.aggregate(symbol, group, message, screen)); }
        };
    }
}
