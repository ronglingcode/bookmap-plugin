package com.bookmap.plugin.rong;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LocalLogWriterTest {
    @TempDir Path directory;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-02T01:30:00Z"), ZoneOffset.ofHours(-7));
    private final List<String> status = new CopyOnWriteArrayList<>(), warnings = new CopyOnWriteArrayList<>();

    private PluginLogEvent event(String session, String text) {
        return new PluginLogEvent(OffsetDateTime.now(clock), session, "AAPL", "Bookmap", text);
    }
    private OutputStream output(Path path) throws IOException {
        return new BufferedOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND));
    }
    private LocalLogWriter writer(long fileLimit, long totalLimit, int capacity, LocalLogWriter.OutputFactory outputs) {
        return new LocalLogWriter(directory, clock, fileLimit, totalLimit, 30, capacity, outputs, status::add, warnings::add);
    }
    private void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Condition timed out; warnings=" + warnings);
    }
    private List<Path> files() throws IOException {
        try (var paths = Files.list(directory)) { return paths.filter(path -> path.toString().endsWith(".log")).sorted().collect(java.util.stream.Collectors.toList()); }
    }
    private String allText() throws IOException {
        StringBuilder text = new StringBuilder(); for (Path path : files()) text.append(Files.readString(path)); return text.toString();
    }

    @Test void concurrentEventsAreCompleteAndSessionsAppendAfterShutdown() throws Exception {
        LocalLogWriter writer = writer(100000, 1000000, 4096, this::output);
        var producers = Executors.newFixedThreadPool(4);
        try {
            for (int producer = 0; producer < 4; producer++) {
                final int id = producer;
                producers.submit(() -> { for (int i = 0; i < 200; i++) assertTrue(writer.append(event("first", "message-" + id + "-" + i + " λ"))); });
            }
            producers.shutdown(); assertTrue(producers.awaitTermination(3, TimeUnit.SECONDS));
        } finally { producers.shutdownNow(); writer.close(); }
        try (LocalLogWriter next = writer(100000, 1000000, 10, this::output)) { assertTrue(next.append(event("second", "restart"))); }
        String text = allText(); assertEquals(801, text.lines().count());
        for (int producer = 0; producer < 4; producer++) for (int i = 0; i < 200; i++) assertTrue(text.contains(" message-" + producer + "-" + i + " λ\n"));
        assertTrue(text.contains("2026-10-01T18:30:00.000-07:00 [session=second] AAPL [Bookmap] restart"));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test void flushesWhileRunningAndUsesCapturedDateAcrossMidnight() throws Exception {
        try (LocalLogWriter writer = writer(10000, 100000, 10, this::output)) {
            writer.append(event("session", "before midnight"));
            Path today = directory.resolve("bmtrader-2026-10-01.log");
            await(() -> { try { return Files.readString(today).contains("before midnight"); } catch (IOException error) { return false; } });
            writer.append(new PluginLogEvent(OffsetDateTime.parse("2026-10-02T00:00:01-07:00"), "session", "", "", "after midnight"));
        }
        assertTrue(Files.readString(directory.resolve("bmtrader-2026-10-02.log")).contains("after midnight"));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test void rotatesAndPrunesOnlyOwnedFilesByAgeAndTotalBytes() throws Exception {
        Path old = directory.resolve("bmtrader-2026-08-31.log"); Files.writeString(old, "old");
        Path unrelated = directory.resolve("notes.txt"); Files.writeString(unrelated, "keep");
        Path previous = directory.resolve("bmtrader-2026-09-30.log"); Files.writeString(previous, "x".repeat(500));
        try (LocalLogWriter writer = writer(260, 600, 100, this::output)) {
            for (int i = 0; i < 35; i++) writer.append(event("session", "rotation-" + i + " " + "x".repeat(45)));
        }
        assertFalse(Files.exists(old)); assertFalse(Files.exists(previous)); assertEquals("keep", Files.readString(unrelated));
        long total = 0; for (Path path : files()) { long bytes = Files.size(path); assertTrue(bytes <= 260); total += bytes; }
        // Retention also accounts for the currently buffered segment.
        assertTrue(total <= 600, "total bytes=" + total);
        assertTrue(allText().contains("rotation-34")); assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test void queueOverflowNeverWaitsForDiskAndCloseDrainsAcceptedEvents() throws Exception {
        CountDownLatch opened = new CountDownLatch(1), release = new CountDownLatch(1);
        LocalLogWriter writer = writer(10000, 100000, 2, path -> {
            opened.countDown();
            try { assertTrue(release.await(3, TimeUnit.SECONDS)); } catch (InterruptedException error) { throw new IOException(error); }
            return output(path);
        });
        try {
            assertTrue(opened.await(2, TimeUnit.SECONDS));
            assertTrue(writer.append(event("s", "first"))); assertTrue(writer.append(event("s", "second")));
            assertFalse(writer.append(event("s", "overflow")));
            writer.requestClose(); assertFalse(writer.append(event("s", "after close")));
        } finally { release.countDown(); writer.close(); }
        String text = allText(); assertTrue(text.contains("first")); assertTrue(text.contains("second")); assertFalse(text.contains("overflow"));
        assertTrue(warnings.stream().anyMatch(line -> line.contains("lost 1")), warnings.toString());
    }

    @Test void unwritableDirectoryReportsFailureWithoutThrowingToCaller() throws Exception {
        Path blocked = directory.resolve("blocked"); Files.writeString(blocked, "file, not directory");
        try (LocalLogWriter writer = new LocalLogWriter(blocked, status::add, warnings::add)) {
            await(() -> warnings.stream().anyMatch(line -> line.contains("unavailable")));
            assertTrue(writer.append(event("s", "screen can continue")));
        }
        assertTrue(status.stream().anyMatch(line -> line.contains("unavailable")));
        assertEquals("file, not directory", Files.readString(blocked));
    }

    @Test void storageRecoversAutomaticallyAndWarningsAreRateLimited() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        try (LocalLogWriter writer = writer(10000, 100000, 100, path -> {
            if (attempts.incrementAndGet() == 1) throw new IOException("simulated disk failure");
            return output(path);
        })) {
            await(() -> !warnings.isEmpty());
            for (int i = 0; i < 10; i++) writer.append(event("s", "lost during failure"));
            await(() -> status.stream().anyMatch(line -> line.contains("file opened; writes are buffered")));
            writer.append(event("s", "recovered"));
            assertEquals(1, warnings.size(), warnings.toString());
        }
        assertTrue(allText().contains("recovered"));
    }

    @Test void sanitizesBothSinksAndPreservesTheSameTimestamp() {
        PluginLogEvent event = event("s", "Bearer secret access_token=abc https://fake.invalid/callback?code=xyz&key=123\nforged line");
        assertTrue(event.screenLine().startsWith("18:30:00.000 AAPL [Bookmap]"));
        for (String line : List.of(event.screenLine(), event.fileLine())) {
            assertFalse(line.contains("secret")); assertFalse(line.contains("abc")); assertFalse(line.contains("xyz")); assertFalse(line.contains("123")); assertFalse(line.contains("\n"));
        }
        assertEquals(Path.of(System.getProperty("user.home"), "bmtrader", "logs"), PluginLog.directory());
    }
}
