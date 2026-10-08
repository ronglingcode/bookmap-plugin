package com.bookmap.plugin.rong.signal;

import com.google.gson.JsonObject;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/** Worker-owned delivery and optional capture of aggregate snapshots only. */
public final class CairoPatternFeed implements AutoCloseable {
    private final Function<Boolean, JsonObject> reader;
    private final boolean capture;
    private final String source = UUID.randomUUID().toString();
    private long lastDelivery, bytes;
    private int segment;
    private Path file;
    private volatile boolean closed;
    private String error;
    private volatile JsonObject latest;
    public CairoPatternFeed(Function<Boolean, JsonObject> reader, boolean capture) { this.reader = reader; this.capture = capture; }
    public List<JsonObject> snapshot() {
        JsonObject value = reader.apply(true);
        if (value == null) return Collections.emptyList();
        value.addProperty("captureEnabled", capture);
        if (error != null) value.addProperty("captureError", error);
        latest = value;
        return Collections.singletonList(value);
    }
    public List<JsonObject> drain(long now) {
        if (closed || now - lastDelivery < 500) return Collections.emptyList();
        lastDelivery = now;
        JsonObject value = reader.apply(false);
        if (value == null) return Collections.emptyList();
        latest = value;
        if (capture && error == null) persist(value);
        value.addProperty("captureEnabled", capture);
        if (error != null) value.addProperty("captureError", error);
        return Collections.singletonList(value);
    }
    private void persist(JsonObject value) {
        try {
            Path root = Path.of(System.getProperty("user.home"), "bmtrader", "patterns"); Files.createDirectories(root);
            byte[] data = (value.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            if (file == null || bytes + data.length > 10 * 1024 * 1024) {
                file = root.resolve("patterns-" + source + "-" + segment++ + ".jsonl"); bytes = 0;
                List<Path> files = new ArrayList<>();
                try (java.util.stream.Stream<Path> paths = Files.list(root)) {
                    paths.filter(p -> p.getFileName().toString().matches("patterns-[a-f0-9-]+-[0-9]+\\.jsonl")).forEach(files::add);
                }
                files.sort(Comparator.comparingLong(p -> { try { return Files.getLastModifiedTime(p).toMillis(); } catch (Exception ignored) { return Long.MAX_VALUE; } }));
                long total = 0; for (Path p : files) total += Files.size(p);
                for (Path p : files) { if (total <= 90L * 1024 * 1024) break; long length = Files.size(p); Files.delete(p); total -= length; }
            }
            Files.write(file, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND); bytes += data.length;
        } catch (Exception exception) { error = "Aggregate capture failed: " + exception.getClass().getSimpleName(); }
    }
    public JsonObject terminal() {
        JsonObject previous = latest;
        if (previous == null) return null;
        JsonObject value = previous.deepCopy(); value.addProperty("epoch", value.get("epoch").getAsLong() + 1);
        value.addProperty("sequence", 1); value.addProperty("readiness", "not-ready"); value.addProperty("coverage", "warming");
        value.add("patterns", new com.google.gson.JsonArray()); value.add("signals", new com.google.gson.JsonArray());
        value.add("contexts", new com.google.gson.JsonArray()); value.add("candidateStates", new JsonObject());
        return value;
    }
    public void close() { closed = true; }
}
