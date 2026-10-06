package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.SignalComposerActivationTest.*;
import static com.bookmap.plugin.rong.SignalComposerSettingsTest.checkbox;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.bookmap.plugin.rong.signal.SignalComposerConfig;
import velox.gui.StrategyPanel;

class SignalComposerObserverSettingsTest {
    @TempDir Path directory;
    private List<String> buttons(Container container) {
        List<String> names = new ArrayList<>();
        for (Component component : container.getComponents()) {
            if (component instanceof JButton && ((JButton)component).getText() != null
                    && !((JButton)component).getText().isBlank()) names.add(((JButton)component).getText());
            if (component instanceof Container) names.addAll(buttons((Container)component));
        }
        return names;
    }
    @Test void credentialFreeObserverUsesExistingFlagAndShowsNoNativeActions() throws Exception {
        String oldObservation = System.getProperty("bmtrader.observationConfig"), oldSecrets = System.getProperty("bmtrader.secrets");
        Object oldThreshold = field("wallThresholdConfig").get(null);
        try {
            Path observation = directory.resolve("observation.json");
            Files.writeString(observation, "{\"enabled\":true,\"observerOnly\":true,\"sourceMode\":\"replay\"}");
            System.setProperty("bmtrader.observationConfig", observation.toString());
            System.setProperty("bmtrader.secrets", directory.resolve("missing-secrets.json").toString());
            withIsolatedActivation(() -> {
                System.setProperty(SignalComposerConfig.CONFIG_PROPERTY, directory.resolve("missing-composer.json").toString());
                RongPlugin p = plugin("TEST"); p.initializeSignalComposition(.01);
                StrategyPanel[] panels = p.getCustomSettingsPanels(); assertEquals(1, panels.length);
                assertNotNull(checkbox(panels[0], "SignalComposer (advisory)"));
                assertNotNull(checkbox(panels[0], "Order Wall Size Labels"));
                assertNull(checkbox(panels[0], "Native Trading Notification Sound"));
                assertTrue(buttons(panels[0]).isEmpty()); assertNull(field("nativeTrading").get(null));
                assertNull(field("tradeButtonWindow").get(p));
            });
        } finally {
            field("wallThresholdConfig").set(null, oldThreshold);
            restore("bmtrader.observationConfig", oldObservation); restore("bmtrader.secrets", oldSecrets);
        }
    }
    @Test void ordinaryPanelStillHasNativeControls() {
        IndicatorSettingsPanel p = new IndicatorSettingsPanel(new IndicatorConfig(), new WallThresholdConfig());
        assertTrue(buttons(p).contains("Restart Native Trading / Reload Secrets"));
    }
    private static void restore(String property, String value) { if (value == null) System.clearProperty(property); else System.setProperty(property, value); }
}
