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
}
