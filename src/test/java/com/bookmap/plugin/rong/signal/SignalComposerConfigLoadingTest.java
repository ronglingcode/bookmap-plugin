package com.bookmap.plugin.rong.signal;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SignalComposerConfigLoadingTest {
    @TempDir Path directory;
    @Test void missingFileProvidesUsableDisabledDefaults() {
        SignalComposerConfig c = SignalComposerConfig.load(directory.resolve("missing.json"));
        assertTrue(c.valid); assertFalse(c.enabled); assertEquals(3000, c.observationFloorSize);
    }
    @Test void loadsOnlyTheExplicitTemporaryFile() throws IOException {
        Path file = directory.resolve("config.json"); Files.writeString(file, "{\"enabled\":true,\"symbols\":[\"TEST\"]}");
        SignalComposerConfig c = SignalComposerConfig.load(file);
        assertTrue(c.enabled); assertTrue(c.eligible("TEST")); assertFalse(c.eligible("OTHER"));
    }
    @Test void malformedAndOversizedFilesCannotBeEnabled() throws IOException {
        Path file = directory.resolve("bad.json");
        for (String content : new String[]{"[1]", "null", "{broken", "{\"normalTriggerSize\":-1}", " ".repeat(SignalComposerConfig.MAX_CONFIG_BYTES + 1)}) {
            Files.writeString(file, content); SignalComposerConfig c = SignalComposerConfig.load(file);
            assertFalse(c.valid); assertFalse(c.enabled); assertFalse(c.error.isBlank());
        }
    }
    @Test void testProcessNeverInheritsTheUsersLocalFile() {
        String configured = System.getProperty(SignalComposerConfig.CONFIG_PROPERTY);
        assertNotNull(configured); assertTrue(configured.endsWith("no-signal-composer-config.json"));
        assertFalse(SignalComposerConfig.load().enabled);
    }
}
