package com.bookmap.plugin.common;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Simple file logger for the plugin. Writes to ~/Bookmap/Logs/plugin.log
 * so plugin output is separated from Bookmap's own system logs.
 */
public class PluginLog {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static PrintWriter writer;
    private static boolean initFailed = false;

    private PluginLog() {}

    private static synchronized PrintWriter getWriter() {
        if (writer != null) return writer;
        if (initFailed) return null;

        try {
            Path logDir = Paths.get(System.getProperty("user.home"), "Bookmap", "Logs");
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve("plugin.log");
            writer = new PrintWriter(new FileWriter(logFile.toFile(), true), true);
        } catch (IOException e) {
            initFailed = true;
            System.err.println("[PluginLog] Failed to open log file: " + e.getMessage());
        }
        return writer;
    }

    public static void info(String msg) {
        log("INFO", msg);
    }

    public static void error(String msg) {
        log("ERROR", msg);
    }

    public static void error(String msg, Throwable t) {
        log("ERROR", msg + ": " + t.getMessage());
    }

    private static void log(String level, String msg) {
        String line = LocalDateTime.now().format(TIMESTAMP_FMT) + " [" + level + "] " + msg;
        PrintWriter w = getWriter();
        if (w != null) {
            w.println(line);
        }
        // Also write to stdout/stderr so it still appears in Bookmap logs during debugging
        if ("ERROR".equals(level)) {
            System.err.println(line);
        } else {
            System.out.println(line);
        }
    }
}
