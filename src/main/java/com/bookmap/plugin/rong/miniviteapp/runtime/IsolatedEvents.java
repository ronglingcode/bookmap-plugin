package com.bookmap.plugin.rong.miniviteapp.runtime;

import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** UI/logging/notification failures cannot change broker-result classification or stop timers. */
final class IsolatedEvents implements TradingRuntime.Events {
    private final TradingRuntime.Events target;
    private final AtomicLong failures = new AtomicLong();
    private final java.util.concurrent.Executor deferred;
    IsolatedEvents(TradingRuntime.Events target, java.util.concurrent.Executor deferred) { this.target = target; this.deferred = deferred; }
    long failures() { return failures.get(); }
    private void safely(Runnable action) { try { action.run(); } catch (RuntimeException error) { failures.incrementAndGet(); } }
    public void message(JsonObject message) { safely(() -> target.message(message)); }
    public void log(String symbol, String message) { safely(() -> target.log(symbol, message)); }
    private void defer(Runnable action) { safely(() -> deferred.execute(() -> safely(action))); }
    public void notify(String symbol, String message) { defer(() -> target.notify(symbol, message)); }
    public void status(String source, String status) { defer(() -> target.status(source, status)); }
    public void minuteBarsLoaded(String symbol, String date, List<Candle> bars) { defer(() -> target.minuteBarsLoaded(symbol, date, bars)); }
    public void detail(String symbol, String message) { safely(() -> target.detail(symbol, message)); }
    public void summary(String symbol, String message, String screen) { safely(() -> target.summary(symbol, message, screen)); }
    public void transition(String symbol, String message) { safely(() -> target.transition(symbol, message)); }
    public void aggregate(String symbol, String group, String message, String screen) { safely(() -> target.aggregate(symbol, group, message, screen)); }
}
