package com.bookmap.plugin.rong;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Displays action messages and queues the same captured events for local persistence. */
public class PluginLog {

    private static LocalLogWriter writer;
    private static LocalLogWriter draining;
    private static String session = "inactive";
    private static Thread shutdownHook;
    private static volatile String activeSession;

    private PluginLog() {}

    public static void action(String msg) {
        action("", "", msg);
    }

    public static void action(String symbol, String msg) {
        action(symbol, "", msg);
    }

    public static synchronized void action(String symbol, String source, String msg) {
        PluginLogEvent event = new PluginLogEvent(OffsetDateTime.now(), session, symbol, source, msg);
        ActionLogWindow.append(event.screenLine());
        if (writer != null) writer.append(event);
    }

    public static Path directory() {
        return Path.of(System.getProperty("user.home"), "bmtrader", "logs");
    }

    public static synchronized void start() {
        if (writer != null) return;
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
