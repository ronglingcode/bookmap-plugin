package com.bookmap.plugin.rong.patterns;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

import com.bookmap.plugin.rong.PluginLog;

public class PatternSignalLogger implements AutoCloseable {

    private final Path logFile;
    private BufferedWriter writer;

    public PatternSignalLogger() {
        this(Paths.get(System.getProperty("user.home"), "Bookmap", "bookmap-signals",
                "pattern-signals.jsonl"));
    }

    PatternSignalLogger(Path logFile) {
        this.logFile = logFile;
    }

    public synchronized void append(BookmapPatternSignal signal) {
        try {
            ensureWriter();
            writer.write(signal.toJson().toString());
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            PluginLog.error("[PatternSignal] Failed to append " + logFile + ": " + e.getMessage());
        }
    }

    private void ensureWriter() throws IOException {
        if (writer != null) {
            return;
        }
        Files.createDirectories(logFile.getParent());
        writer = Files.newBufferedWriter(
                logFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    @Override
    public synchronized void close() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException e) {
            PluginLog.error("[PatternSignal] Failed to close " + logFile + ": " + e.getMessage());
        } finally {
            writer = null;
        }
    }
}
