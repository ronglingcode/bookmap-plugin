package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.bookmap.plugin.rong.signal.SignalComposerConfig;

class SignalComposerActivationTest {
    @TempDir Path directory;
    static Field field(String name) throws Exception {
        Field f = RongPlugin.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
    @Test void firstAttachmentLoadsRulesAndLaterAttachmentKeepsUserToggleAndSnapshot() throws Exception {
        withIsolatedActivation(() -> {
            Path config = directory.resolve("composer.json"); Files.writeString(config, "{\"enabled\":true}");
            System.setProperty(SignalComposerConfig.CONFIG_PROPERTY, config.toString());
            RongPlugin first = plugin("TEST"); first.initializeSignalComposition(.01);
            SignalComposerConfig rules = (SignalComposerConfig)field("signalComposerConfig").get(null);
            IndicatorConfig indicators = (IndicatorConfig)field("indicatorConfig").get(null);
            assertTrue(indicators.isEnabled(IndicatorConfig.SIGNAL_COMPOSER));
            indicators.setEnabled(IndicatorConfig.SIGNAL_COMPOSER, false);
            Files.writeString(config, "{\"enabled\":true,\"normalConfirmationSize\":7000}");
            RongPlugin second = plugin("OTHER"); second.initializeSignalComposition(.01);
            assertSame(rules, field("compositionRules").get(second));
            assertFalse(field("signalCompositionEnabled").getBoolean(second));
            assertNotSame(field("signalComposition").get(first), field("signalComposition").get(second));
            assertNull(field("nativeTrading").get(null));
        });
    }
    @Test void missingDefaultsConstructDisabledAndMalformedConfigDisarms() throws Exception {
        withIsolatedActivation(() -> {
            System.setProperty(SignalComposerConfig.CONFIG_PROPERTY, directory.resolve("missing.json").toString());
            RongPlugin missing = plugin("TEST"); missing.initializeSignalComposition(.01);
            assertNotNull(field("signalComposition").get(missing)); assertFalse(field("signalCompositionEnabled").getBoolean(missing));
        });
        withIsolatedActivation(() -> {
            Path config = directory.resolve("invalid.json"); Files.writeString(config, "{\"enabled\":true,\"minimumTriggerSize\":0}");
            System.setProperty(SignalComposerConfig.CONFIG_PROPERTY, config.toString());
            RongPlugin invalid = plugin("TEST"); invalid.initializeSignalComposition(.01);
            assertNull(field("signalComposition").get(invalid));
            assertFalse(((IndicatorConfig)field("indicatorConfig").get(null)).isEnabled(IndicatorConfig.SIGNAL_COMPOSER));
        });
    }
    @Test void symbolFilterAndInvalidTickSizeDoNotConstructObservers() throws Exception {
        withIsolatedActivation(() -> {
            Path config = directory.resolve("filtered.json"); Files.writeString(config, "{\"enabled\":true,\"symbols\":[\"TEST\"]}");
            System.setProperty(SignalComposerConfig.CONFIG_PROPERTY, config.toString());
            RongPlugin other = plugin("OTHER"); other.initializeSignalComposition(.01); assertNull(field("signalComposition").get(other));
            RongPlugin bad = plugin("TEST"); bad.initializeSignalComposition(Double.NaN); assertNull(field("signalComposition").get(bad));
        });
    }
    static RongPlugin plugin(String alias) throws Exception { RongPlugin p = new RongPlugin(); field("alias").set(p, alias); return p; }
    interface Checked { void run() throws Exception; }
    static void withIsolatedActivation(Checked action) throws Exception {
        String property = System.getProperty(SignalComposerConfig.CONFIG_PROPERTY);
        String[] names = {"signalComposerConfig", "tradingSignalStore", "indicatorConfig"};
        Object[] saved = new Object[names.length];
        for (int i = 0; i < names.length; i++) { saved[i] = field(names[i]).get(null); field(names[i]).set(null, null); }
        try { action.run(); } finally {
            for (int i = 0; i < names.length; i++) field(names[i]).set(null, saved[i]);
            if (property == null) System.clearProperty(SignalComposerConfig.CONFIG_PROPERTY); else System.setProperty(SignalComposerConfig.CONFIG_PROPERTY, property);
        }
    }
}
