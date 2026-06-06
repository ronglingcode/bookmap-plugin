package com.bookmap.plugin.rong.exporter;

import com.bookmap.plugin.rong.PluginLog;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

class BookmapExportWriter {

    static final int SCHEMA_VERSION = 1;

    private final Path metadataPath;
    private final Path eventsPath;
    private final BufferedWriter writer;
    private final int flushEvery;
    private long pendingEvents;
    private boolean closed;

    private BookmapExportWriter(Path metadataPath, Path eventsPath, BufferedWriter writer, int flushEvery) {
        this.metadataPath = metadataPath;
        this.eventsPath = eventsPath;
        this.writer = writer;
        this.flushEvery = Math.max(1, flushEvery);
    }

    static BookmapExportWriter open(String runId, String symbol, String metadataJson, int flushEvery) throws IOException {
        Path symbolDir = resolveOutputRoot()
                .resolve(runId)
                .resolve(safePathSegment(symbol));
        Files.createDirectories(symbolDir);

        UniquePaths paths = resolveUniquePaths(symbolDir);
        Files.write(
                paths.metadataPath,
                metadataJson.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);

        BufferedWriter writer = Files.newBufferedWriter(
                paths.eventsPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);

        PluginLog.info("[BacktestExporter] Writing " + paths.eventsPath);
        return new BookmapExportWriter(paths.metadataPath, paths.eventsPath, writer, flushEvery);
    }

    synchronized void writeLine(String json) {
        if (closed) {
            return;
        }
        try {
            writer.write(json);
            writer.newLine();
            pendingEvents++;
            if (pendingEvents >= flushEvery) {
                flush();
            }
        } catch (IOException e) {
            PluginLog.error("[BacktestExporter] Failed to write export event", e);
        }
    }

    synchronized void flush() {
        if (closed) {
            return;
        }
        try {
            writer.flush();
            pendingEvents = 0;
        } catch (IOException e) {
            PluginLog.error("[BacktestExporter] Failed to flush export events", e);
        }
    }

    synchronized void close() {
        if (closed) {
            return;
        }
        try {
            writer.flush();
            writer.close();
        } catch (IOException e) {
            PluginLog.error("[BacktestExporter] Failed to close export writer", e);
        } finally {
            closed = true;
            PluginLog.info("[BacktestExporter] Closed " + eventsPath);
        }
    }

    Path getMetadataPath() {
        return metadataPath;
    }

    Path getEventsPath() {
        return eventsPath;
    }

    static Path resolveOutputRoot() {
        String configured = System.getProperty("bookmap.export.dir", "");
        if (configured.trim().isEmpty()) {
            return Paths.get(System.getProperty("user.home"), "Bookmap", "backtest-exports");
        }
        String value = configured.trim();
        if (value.equals("~")) {
            return Paths.get(System.getProperty("user.home"));
        }
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            return Paths.get(System.getProperty("user.home"), value.substring(2));
        }
        return Paths.get(value);
    }

    static int intProperty(String propertyName, int defaultValue, int minValue) {
        String raw = System.getProperty(propertyName);
        if (raw == null || raw.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Math.max(minValue, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            PluginLog.error("[BacktestExporter] Invalid integer property " + propertyName
                    + "=" + raw + "; using " + defaultValue);
            return defaultValue;
        }
    }

    static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static UniquePaths resolveUniquePaths(Path symbolDir) {
        Path metadata = symbolDir.resolve("metadata.json");
        Path events = symbolDir.resolve("events.jsonl");
        if (!Files.exists(metadata) && !Files.exists(events)) {
            return new UniquePaths(metadata, events);
        }
        for (int i = 2; i < 10_000; i++) {
            metadata = symbolDir.resolve("metadata-" + i + ".json");
            events = symbolDir.resolve("events-" + i + ".jsonl");
            if (!Files.exists(metadata) && !Files.exists(events)) {
                return new UniquePaths(metadata, events);
            }
        }
        throw new IllegalStateException("Could not allocate unique export file under " + symbolDir);
    }

    private static String safePathSegment(String value) {
        String raw = (value == null || value.trim().isEmpty()) ? "UNKNOWN" : value.trim();
        return raw.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private static class UniquePaths {
        private final Path metadataPath;
        private final Path eventsPath;

        private UniquePaths(Path metadataPath, Path eventsPath) {
            this.metadataPath = metadataPath;
            this.eventsPath = eventsPath;
        }
    }
}
