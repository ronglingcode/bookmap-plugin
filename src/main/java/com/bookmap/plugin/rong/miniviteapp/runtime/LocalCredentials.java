package com.bookmap.plugin.rong.miniviteapp.runtime;

import com.bookmap.plugin.rong.miniviteapp.ports.CredentialPort;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** User-owned JSON file; preserve every unrelated field when rotating broker tokens. */
public final class LocalCredentials implements CredentialPort {
    private final Path path;
    private JsonObject values;
    public LocalCredentials() throws IOException { this(defaultPath()); }
    public LocalCredentials(Path path) throws IOException { this.path = path.toAbsolutePath().normalize(); reload(); }
    public static Path defaultPath() {
        String override = System.getProperty("bmtrader.secrets", "");
        return override.isBlank() ? Path.of(System.getProperty("user.home"), "bmtrader", "secrets.json") : Path.of(override);
    }
    public static boolean fileExists() {
        try { return Files.isRegularFile(defaultPath()); }
        catch (SecurityException | java.nio.file.InvalidPathException ignored) { return false; }
    }
    public synchronized void reload() throws IOException {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.startsWith("\uFEFF")) content = content.substring(1);
            values = JsonParser.parseString(content).getAsJsonObject();
        } catch (RuntimeException error) { throw new IOException("Local secrets file is not a JSON object: " + path); }
    }
    public synchronized JsonObject section(String name) {
        return values.has(name) && values.get(name).isJsonObject() ? values.getAsJsonObject(name).deepCopy() : new JsonObject();
    }
    @Override public synchronized JsonObject loadSchwab() { return section("schwab"); }
    @Override public synchronized void saveSchwab(JsonObject credentials) throws IOException {
        // Refresh only updates the Schwab section; retain user additions in all sections.
        reload();
        JsonObject merged = section("schwab"); credentials.entrySet().forEach(entry -> merged.add(entry.getKey(), entry.getValue().deepCopy()));
        JsonObject updated = values.deepCopy(); updated.add("schwab", merged);
        Path temporary = Files.createTempFile(path.getParent(), "secrets-", ".tmp");
        try {
            byte[] data = (new GsonBuilder().setPrettyPrinting().create().toJson(updated) + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = ByteBuffer.wrap(data); while (bytes.hasRemaining()) file.write(bytes); file.force(true);
            }
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
            values = updated;
        } finally { Files.deleteIfExists(temporary); }
    }
}
