package com.bookmap.plugin.rong.tradebuttons;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JLabel;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.SignalWebSocketServer;
import static org.junit.jupiter.api.Assertions.*;

class HotkeyRiskPanelTest {
    private List<JRadioButton> radios(HotkeyRiskPanel panel) {
        return Arrays.stream(panel.getComponents()).filter(JRadioButton.class::isInstance)
                .map(JRadioButton.class::cast).collect(java.util.stream.Collectors.toList());
    }

    private void assertSelection(HotkeyRiskPanel panel, String method) {
        assertEquals(1, radios(panel).stream().filter(JRadioButton::isSelected).count());
        assertEquals(method, radios(panel).stream().filter(JRadioButton::isSelected).findFirst().get().getText());
        assertEquals("Hotkey risk: " + method, ((JLabel) panel.getComponent(0)).getText());
    }

    @Test void selectionIsExclusiveSharedAndSurvivesPanelRebuildsWithoutSubmittingOrders() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
            AtomicInteger dispatches = new AtomicInteger();
            server.setTradingDispatch(action -> dispatches.incrementAndGet());
            HotkeyRiskSelection selection = server.getHotkeyRiskSelection();
            HotkeyRiskPanel first = new HotkeyRiskPanel(selection);
            HotkeyRiskPanel second = new HotkeyRiskPanel(selection);
            assertSelection(first, "1 R");
            for (JRadioButton radio : radios(first)) {
                radio.doClick();
                assertSelection(first, radio.getText());
                assertSelection(second, radio.getText());
                assertEquals(radio.getText(), selection.getEntryMethod());
            }
            first.dispose();
            HotkeyRiskPanel rebuilt = new HotkeyRiskPanel(selection);
            assertSelection(rebuilt, "0.1 R");
            radios(second).get(1).doClick();
            assertSelection(rebuilt, "0.5 R");
            assertSelection(first, "0.1 R"); // Disposed panels no longer subscribe.
            assertEquals(0, dispatches.get());
            assertEquals("1 R", new SignalWebSocketServer(0, 90).getHotkeyRiskSelection().getEntryMethod());
            second.dispose();
            rebuilt.dispose();
        });
    }

    @Test void unsupportedRiskCannotReplaceTheCurrentSelection() {
        HotkeyRiskSelection selection = new HotkeyRiskSelection();
        selection.setEntryMethod("0.5 R");
        assertThrows(IllegalArgumentException.class, () -> selection.setEntryMethod("2 R"));
        assertEquals("0.5 R", selection.getEntryMethod());
    }
}
