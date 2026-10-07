package com.bookmap.plugin.rong.signal;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SignalComposerConfigLoadingTest {
    @Test void shippedEnabledTemplateMatchesEveryEffectiveDefaultUnderRealLoader() {
        SignalComposerConfig template = SignalComposerConfig.load(Path.of("config", "signal-composer.template.json"));
        assertTrue(template.valid, template.error); assertTrue(template.enabled);
        assertEquals(SignalComposerConfig.defaults().toJson(), template.toJson());
        assertEquals(SignalComposerConfig.defaults().revision, template.revision);
    }
    @TempDir Path directory;
    @Test void missingFileProvidesUsableEnabledDefaults() {
        SignalComposerConfig c = SignalComposerConfig.load(directory.resolve("missing.json"));
        assertTrue(c.valid); assertTrue(c.enabled); assertEquals(1000, c.observationFloorSize);
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
        assertTrue(SignalComposerConfig.load().enabled);
    }
    @Test void defaultStartupUsesShippedRulesEvenWithAStaleHomeDirectoryFile() throws IOException {
        String configured = System.getProperty(SignalComposerConfig.CONFIG_PROPERTY);
        String previousHome = System.getProperty("user.home");
        Path home = directory.resolve("home");
        Path rules = home.resolve("bmtrader").resolve("signal-composer.json");
        Files.createDirectories(rules.getParent());
        Files.writeString(rules, "{\"enabled\":false,\"detectors\":{\"holdApproachRatio\":0.01,\"holdRetreatRatio\":0.02,\"interactionWindowMs\":5000}}");
        try {
            System.setProperty("user.home", home.toString());
            System.clearProperty(SignalComposerConfig.CONFIG_PROPERTY);
            assertEquals(SignalComposerConfig.defaults().toJson(), SignalComposerConfig.load().toJson());
            Files.writeString(rules, "{broken");
            assertEquals(SignalComposerConfig.defaults().revision, SignalComposerConfig.load().revision);
            System.setProperty(SignalComposerConfig.CONFIG_PROPERTY, " ");
            assertEquals(SignalComposerConfig.defaults().revision, SignalComposerConfig.load().revision);
            // Other settings can still be overridden by deliberately opting in to a file.
            Files.writeString(rules, "{\"enabled\":false}");
            System.setProperty(SignalComposerConfig.CONFIG_PROPERTY, rules.toString());
            assertFalse(SignalComposerConfig.load().enabled);
        } finally {
            if (previousHome == null) System.clearProperty("user.home"); else System.setProperty("user.home", previousHome);
            if (configured == null) System.clearProperty(SignalComposerConfig.CONFIG_PROPERTY);
            else System.setProperty(SignalComposerConfig.CONFIG_PROPERTY, configured);
        }
    }
}
