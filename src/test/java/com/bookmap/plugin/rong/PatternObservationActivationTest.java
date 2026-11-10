package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.bookmap.plugin.rong.patterns.PatternObservationConfig;

class PatternObservationActivationTest {
    @TempDir Path directory;
    static Field field(String name) throws Exception {
        Field f = RongPlugin.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
    @Test void attachmentsShareDetectorSettingsButHaveIndependentObservers() throws Exception {
        withIsolatedActivation(() -> {
            Path config = directory.resolve("patterns.json"); Files.writeString(config, "{\"enabled\":true}");
            System.setProperty(PatternObservationConfig.CONFIG_PROPERTY, config.toString());
            RongPlugin first = plugin("TEST"); first.initializePatternEvents(.01);
            Object rules = field("patternEventConfig").get(null);
            Files.writeString(config, "{\"enabled\":false}");
            RongPlugin second = plugin("OTHER"); second.initializePatternEvents(.01);
            assertSame(rules, field("patternRules").get(second));
            assertNotSame(field("patternEvents").get(first), field("patternEvents").get(second));
            assertNull(field("nativeTrading").get(null));
        });
    }
    @Test void malformedDisabledFilteredAndInvalidTickAttachmentsDoNotObserve() throws Exception {
        for (String json : new String[]{"{", "{\"enabled\":false}", "{\"symbols\":[\"OTHER\"]}"}) {
            withIsolatedActivation(() -> {
                Path config = directory.resolve("patterns.json"); Files.writeString(config, json);
                System.setProperty(PatternObservationConfig.CONFIG_PROPERTY, config.toString());
                RongPlugin p = plugin("TEST"); p.initializePatternEvents(.01);
                assertNull(field("patternEvents").get(p));
            });
        }
        withIsolatedActivation(() -> {
            RongPlugin p = plugin("TEST"); p.initializePatternEvents(Double.NaN);
            assertNull(field("patternEvents").get(p));
        });
    }
    static RongPlugin plugin(String alias) throws Exception { RongPlugin p = new RongPlugin(); field("alias").set(p, alias); return p; }
    interface Checked { void run() throws Exception; }
    static void withIsolatedActivation(Checked action) throws Exception {
        String property = System.getProperty(PatternObservationConfig.CONFIG_PROPERTY);
        String[] names = {"patternEventConfig", "indicatorConfig"}; Object[] saved = new Object[names.length];
        for (int i = 0; i < names.length; i++) { saved[i] = field(names[i]).get(null); field(names[i]).set(null, null); }
        try { action.run(); } finally {
            for (int i = 0; i < names.length; i++) field(names[i]).set(null, saved[i]);
            if (property == null) System.clearProperty(PatternObservationConfig.CONFIG_PROPERTY); else System.setProperty(PatternObservationConfig.CONFIG_PROPERTY, property);
        }
    }
}
