package com.bookmap.plugin.rong;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** All file I/O, rotation and retention run on this sink's single daemon worker. */
final class LocalLogWriter implements AutoCloseable {
    static final long FILE_LIMIT = 10L * 1024 * 1024, TOTAL_LIMIT = 100L * 1024 * 1024;
    private static final Pattern LOG_FILE = Pattern.compile("bmtrader-\\d{4}-\\d{2}-\\d{2}(?:-\\d+)?\\.log");
    interface OutputFactory { OutputStream open(Path path) throws IOException; }
    private final Path directory;
    private final Clock clock;
    private final long fileLimit, totalLimit;
    private final int days;
    private final OutputFactory outputs;
    private final Consumer<String> status, warning;
    private final ArrayBlockingQueue<PluginLogEvent> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final Thread worker;
    private volatile boolean closing;
    private OutputStream output;
    private Path current;
    private LocalDate currentDate;
    private long size, totalBytes, nextRetry, nextWarning, nextRetention;

    LocalLogWriter(Path directory, Consumer<String> status, Consumer<String> warning) {
        this(directory, status, warning, null);
    }

    LocalLogWriter(Path directory, Consumer<String> status, Consumer<String> warning, LocalLogWriter previous) {
        this(directory, Clock.systemDefaultZone(), FILE_LIMIT, TOTAL_LIMIT, 30, 4096,
                path -> new BufferedOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND)), status, warning, previous);
    }

    LocalLogWriter(Path directory, Clock clock, long fileLimit, long totalLimit, int days, int capacity,
            OutputFactory outputs, Consumer<String> status, Consumer<String> warning) {
        this(directory, clock, fileLimit, totalLimit, days, capacity, outputs, status, warning, null);
    }

    private LocalLogWriter(Path directory, Clock clock, long fileLimit, long totalLimit, int days, int capacity,
            OutputFactory outputs, Consumer<String> status, Consumer<String> warning, LocalLogWriter previous) {
        this.directory = directory; this.clock = clock; this.fileLimit = fileLimit; this.totalLimit = totalLimit;
        this.days = days; this.outputs = outputs; this.status = status; this.warning = warning;
        queue = new ArrayBlockingQueue<>(capacity);
        worker = new Thread(() -> {
            try {
                if (previous != null) previous.awaitStopped();
                run();
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }, "bmtrader-local-logs"); worker.setDaemon(true); worker.start();
    }

    synchronized boolean append(PluginLogEvent event) {
        if (closing) return false;
        if (queue.offer(event)) return true;
        dropped.incrementAndGet(); return false;
    }

    synchronized void requestClose() { closing = true; }

    void awaitStopped() throws InterruptedException { worker.join(); }

    @Override public void close() {
        requestClose();
        try { worker.join(3000); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        if (worker.isAlive()) warning.accept("Local logging shutdown timed out; pending entries may be lost");
    }

    private void run() {
        long nextFlush = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        try {
            // Open before the first event, making path/permission failures immediately visible.
            try { open(LocalDate.now(clock), 0); } catch (IOException | RuntimeException error) { failed(error); }
            while (!closing || !queue.isEmpty()) {
                PluginLogEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
                if (event != null) write(event);
                long now = System.nanoTime();
                if (output == null && now >= nextRetry) {
                    try { open(LocalDate.now(clock), 0); } catch (IOException | RuntimeException error) { failed(error); }
                }
                if (now >= nextFlush) {
                    try { if (output != null) output.flush(); } catch (IOException | RuntimeException error) { failed(error); }
                    nextFlush = now + TimeUnit.SECONDS.toNanos(1);
                }
                if (output != null && now >= nextRetention) {
                    try { output.flush(); retain(); } catch (IOException | RuntimeException error) { failed(error); }
                    nextRetention = now + TimeUnit.MINUTES.toNanos(1);
                }
                long lost = dropped.get();
                if (lost > 0 && now >= nextWarning) {
                    lost = dropped.getAndSet(0);
                    warning.accept("Local logging lost " + lost + " file entries (queue full or storage unavailable); screen logging continues");
                    nextWarning = now + TimeUnit.SECONDS.toNanos(30);
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            try { closeOutput(); } catch (IOException error) { warning.accept("Local logging final flush failed: " + error.getClass().getSimpleName()); }
            long lost = dropped.getAndSet(0);
            if (lost > 0) warning.accept("Local logging stopped with " + lost + " unsaved file entries");
        }
    }

    private void write(PluginLogEvent event) {
        byte[] bytes = (event.fileLine() + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            if (output == null && System.nanoTime() < nextRetry) { dropped.incrementAndGet(); return; }
            LocalDate date = event.timestamp.toLocalDate();
            if (output == null || !date.equals(currentDate) || size + bytes.length > fileLimit) {
                closeOutput(); open(date, bytes.length);
            }
            output.write(bytes); size += bytes.length; totalBytes += bytes.length;
            if (totalBytes > totalLimit) retain();
        } catch (IOException | RuntimeException error) { dropped.incrementAndGet(); failed(error); }
    }

    private void open(LocalDate date, int incomingBytes) throws IOException {
        Files.createDirectories(directory);
        List<Path> files = logFiles();
        int part = 0;
        String base = "bmtrader-" + date;
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (name.equals(base + ".log")) continue;
            if (name.startsWith(base + "-")) part = Math.max(part, Integer.parseInt(name.substring(base.length() + 1, name.length() - 4)));
        }
        Path path;
        do {
            path = directory.resolve(base + (part == 0 ? "" : "-" + part) + ".log");
            size = Files.exists(path) ? Files.size(path) : 0;
            if (size == 0 || size + incomingBytes < fileLimit) break;
            part++;
        } while (true);
        output = outputs.open(path); current = path; currentDate = date;
        retain();
        status.accept("Local log file opened; writes are buffered at " + directory);
    }

    private List<Path> logFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (var paths = Files.newDirectoryStream(directory)) {
            for (Path path : paths) if (LOG_FILE.matcher(path.getFileName().toString()).matches()
                    && Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) files.add(path);
        }
        // Date then numeric segment: lexical sorting would incorrectly put -10 before -2.
        files.sort(Comparator.comparing((Path path) -> path.getFileName().toString().substring(9, 19))
                .thenComparingInt(path -> { String name = path.getFileName().toString(); return name.charAt(19) == '.' ? 0 : Integer.parseInt(name.substring(20, name.length() - 4)); }));
        return files;
    }

    private void retain() throws IOException {
        List<Path> files = logFiles(); long total = 0;
        LocalDate cutoff = LocalDate.now(clock).minusDays(days - 1L);
        for (Path path : files) total += path.equals(current) ? size : Files.size(path);
        for (Path path : files) {
            if (path.equals(current)) continue;
            LocalDate date = LocalDate.parse(path.getFileName().toString().substring(9, 19));
            if (date.isBefore(cutoff) || total > totalLimit) { long bytes = Files.size(path); Files.delete(path); total -= bytes; }
        }
        totalBytes = total;
    }

    private void failed(Exception error) {
        try { closeOutput(); } catch (IOException ignored) { }
        status.accept("Local logs: unavailable; retrying in 5 seconds");
        long now = System.nanoTime(); nextRetry = now + TimeUnit.SECONDS.toNanos(5);
        if (now >= nextWarning) {
            warning.accept("Local logging unavailable at " + directory + ": " + error.getClass().getSimpleName() + "; screen logging continues");
            nextWarning = now + TimeUnit.SECONDS.toNanos(30);
        }
    }

    private void closeOutput() throws IOException {
        OutputStream previous = output; output = null;
        if (previous != null) previous.close();
    }
}
