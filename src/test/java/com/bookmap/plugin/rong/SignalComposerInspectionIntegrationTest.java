package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.SignalComposerActivationTest.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.bookmap.plugin.rong.signal.SignalComposerConfig;

class SignalComposerInspectionIntegrationTest {
    @TempDir Path directory;
    @Test void inspectionReportsPerAttachmentEligibilityToggleAndInvalidConfig() throws Exception {
        withIsolatedActivation(() -> {
            Path config = directory.resolve("filtered.json"); Files.writeString(config, "{\"symbols\":[\"TEST\"]}");
            System.setProperty(SignalComposerConfig.CONFIG_PROPERTY, config.toString());
            RongPlugin eligible = plugin("TEST"), excluded = plugin("OTHER");
            field("initialized").setBoolean(eligible, true);
            eligible.initializeSignalComposition(.01); excluded.initializeSignalComposition(.01);
            assertTrue(eligible.signalComposerInspectionText().contains("Enabled"));
            assertTrue(eligible.signalComposerInspectionText().contains("Waiting for snapshot"));
            assertTrue(excluded.signalComposerInspectionText().contains("Excluded by configured symbol filter"));
            eligible.onIndicatorConfigChanged(IndicatorConfig.SIGNAL_COMPOSER, false);
            assertTrue(eligible.signalComposerInspectionText().contains("Disabled"));
            assertTrue(eligible.signalComposerInspectionText().contains("Last reset: DISABLED"));
        });
        withIsolatedActivation(() -> {
            Path config = directory.resolve("invalid.json"); Files.writeString(config, "{broken");
            System.setProperty(SignalComposerConfig.CONFIG_PROPERTY, config.toString());
            RongPlugin invalid = plugin("TEST"); invalid.initializeSignalComposition(.01);
            assertTrue(invalid.signalComposerInspectionText().contains("SignalComposer disabled:"));
        });
    }
}
