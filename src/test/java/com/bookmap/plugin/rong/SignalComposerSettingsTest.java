package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.SignalComposerActivationTest.*;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.nio.file.Files;
import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.bookmap.plugin.rong.signal.SignalComposerConfig;

class SignalComposerSettingsTest {
    @TempDir Path directory;
    static JCheckBox checkbox(Container container, String text) {
        for (Component component : container.getComponents()) {
            if (component instanceof JCheckBox && ((JCheckBox)component).getText().equals(text)) return (JCheckBox)component;
            if (component instanceof Container) { JCheckBox found = checkbox((Container)component, text); if (found != null) return found; }
        }
        return null;
    }
    @Test void missingDefaultsEnableIndependentMasterAndInvalidRulesCannot() throws Exception {
        IndicatorConfig config = new IndicatorConfig();
        IndicatorSettingsPanel panel = new IndicatorSettingsPanel(config, new WallThresholdConfig());
        JCheckBox toggle = checkbox(panel, "SignalComposer (advisory)"); assertNotNull(toggle); assertTrue(toggle.isSelected());
        SwingUtilities.invokeAndWait(toggle::doClick); assertFalse(config.isEnabled(IndicatorConfig.SIGNAL_COMPOSER));
        assertFalse(config.isEnabled(IndicatorConfig.BOOKMAP_PATTERN_SIGNALS));
        SwingUtilities.invokeAndWait(toggle::doClick); assertTrue(config.isEnabled(IndicatorConfig.SIGNAL_COMPOSER));
        IndicatorSettingsPanel invalid = new IndicatorSettingsPanel(config, new WallThresholdConfig(), new NativeConnectionStatus(), SignalComposerConfig.invalid("bad JSON"));
        assertFalse(checkbox(invalid, "SignalComposer (advisory)").isEnabled());
        assertTrue(checkbox(invalid, "SignalComposer (advisory)").getToolTipText().contains("bad JSON"));
    }
    @Test void sharedSwitchAffectsOnlyEligibleAttachmentsAndLaterAttachmentKeepsIt() throws Exception {
        withIsolatedActivation(() -> {
            Path file = directory.resolve("filtered.json"); Files.writeString(file, "{\"symbols\":[\"TEST\"]}");
            System.setProperty(SignalComposerConfig.CONFIG_PROPERTY, file.toString());
            RongPlugin eligible = plugin("TEST"), other = plugin("OTHER");
            field("initialized").setBoolean(eligible, true); field("initialized").setBoolean(other, true);
            eligible.initializeSignalComposition(.01); other.initializeSignalComposition(.01);
            IndicatorConfig config = (IndicatorConfig)field("indicatorConfig").get(null);
            config.addChangeListener(eligible); config.addChangeListener(other);
            config.setEnabled(IndicatorConfig.SIGNAL_COMPOSER, true);
            assertTrue(field("signalCompositionEnabled").getBoolean(eligible)); assertFalse(field("signalCompositionEnabled").getBoolean(other));
            RongPlugin later = plugin("TEST"); later.initializeSignalComposition(.01);
            assertTrue(field("signalCompositionEnabled").getBoolean(later));
            config.setEnabled(IndicatorConfig.SIGNAL_COMPOSER, false);
            assertFalse(field("signalCompositionEnabled").getBoolean(eligible));
            config.removeChangeListener(eligible); config.removeChangeListener(other);
        });
    }
}
