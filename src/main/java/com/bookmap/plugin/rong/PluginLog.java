package com.bookmap.plugin.rong;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Persists full events; only important messages and concise summaries reach the action window. */
public class PluginLog {

    private static LocalLogWriter writer;
    private static LocalLogWriter draining;
    private static String session = "inactive";
    private static Thread shutdownHook;
    private static volatile String activeSession;
    private static final ScreenLogPolicy screen = new ScreenLogPolicy();

    private PluginLog() {}

    public static void action(String msg) {
        action("", "", msg);
    }

    public static void action(String symbol, String msg) {
        action(symbol, "", msg);
    }

    public static void action(String symbol, String source, String msg) {
        summary(symbol, source, msg, msg);
    }

    public static void detail(String symbol, String msg) { detail(symbol, "", msg); }

    public static void detail(String symbol, String source, String msg) {
        summary(symbol, source, msg, null);
    }

    public static void summary(String symbol, String msg, String screenMessage) {
        summary(symbol, "", msg, screenMessage);
    }

    public static void summary(String symbol, String source, String msg, String screenMessage) {
        emit(symbol, source, msg, screenMessage, true, null);
    }

    /** Related updates share a screen row during a burst; every full event still reaches the file. */
    public static void aggregate(String symbol, String group, String msg, String screenMessage) {
        emit(symbol, "", msg, screenMessage, true, symbol + "\n" + group);
    }

    /** State transitions, including a quick disconnect/recovery, must always remain visible. */
    public static void transition(String symbol, String msg) {
        emit(symbol, "", msg, msg, false, null);
    }

    private static synchronized void emit(String symbol, String source, String msg, String screenMessage, boolean suppressRepeats, String group) {
        PluginLogEvent event = new PluginLogEvent(OffsetDateTime.now(), session, symbol, source, msg, screenMessage);
        if (writer != null) writer.append(event);
        String line = screen.line(event, System.nanoTime(), suppressRepeats);
        if (line != null) ActionLogWindow.appendSummary(line, group);
    }

    public static Path directory() {
        return Path.of(System.getProperty("user.home"), "bmtrader", "logs");
    }

    public static synchronized void start() {
        if (writer != null) return;
        screen.clear();
        session = UUID.randomUUID().toString();
        String id = session; activeSession = id;
        writer = new LocalLogWriter(directory(), message -> {
            if (id.equals(activeSession)) ActionLogWindow.updatePersistenceStatus(message);
        }, message -> {
            if (id.equals(activeSession)) ActionLogWindow.append(new PluginLogEvent(OffsetDateTime.now(), id, "", "Logging", message).screenLine());
        }, draining);
        draining = null;
        LocalLogWriter owned = writer;
        shutdownHook = new Thread(owned::close, "bmtrader-logs-shutdown");
        try { Runtime.getRuntime().addShutdownHook(shutdownHook); } catch (IllegalStateException | SecurityException ignored) { }
        action("", "Logging", "Session started; plugin version " + PluginVersion.VERSION);
    }

    public static synchronized void stop() {
        if (writer == null) return;
        action("", "Logging", "Session stopped");
        // Drain on the worker without waiting on Bookmap's UI or trading threads.
        LocalLogWriter owned = writer;
        owned.requestClose(); draining = owned; writer = null; activeSession = null;
        // Leave the bounded shutdown hook until the drain completes, then release the classloader.
        Thread hook = shutdownHook; shutdownHook = null;
        Thread cleanup = new Thread(() -> {
            try { owned.awaitStopped(); Runtime.getRuntime().removeShutdownHook(hook); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            catch (IllegalStateException | SecurityException ignored) { }
        }, "bmtrader-logs-cleanup");
        cleanup.setDaemon(true); cleanup.start();
    }
}
