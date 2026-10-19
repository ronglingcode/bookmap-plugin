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
    @Test void configuredSharesAreSelectableAndDisappearWhenConfigurationIsCleared() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
            AtomicInteger dispatches = new AtomicInteger();
            server.setTradingDispatch(action -> dispatches.incrementAndGet());
            HotkeyRiskSelection selection = server.getHotkeyRiskSelection();
            HotkeyRiskPanel panel = new HotkeyRiskPanel(selection, "AAPL");
            var config = com.google.gson.JsonParser.parseString("{\"type\":\"trade_button_config\",\"symbol\":\"AAPL\",\"tradebooks\":[{\"id\":\"AAPL:RangeBoundBidReversal\",\"label\":\"Reversal\",\"tradebookId\":\"RangeBoundBidReversal\",\"sideIsLong\":true,\"entryMethods\":[\"1 R\",\"50 sh\",\"100 sh\"]}]}").getAsJsonObject();
            server.acceptLocalMessage(config);
            assertEquals(List.of("1 R", "0.5 R", "0.1 R", "50 sh", "100 sh"),
                    radios(panel).stream().map(JRadioButton::getText).collect(java.util.stream.Collectors.toList()));
            radios(panel).get(4).doClick();
            assertSelection(panel, "100 sh");
            assertEquals("100 sh", selection.getEntryMethod("AAPL"));
            assertEquals("1 R", selection.getEntryMethod("MSFT"));
            HotkeyRiskPanel rebuilt = new HotkeyRiskPanel(selection, "AAPL");
            assertSelection(rebuilt, "100 sh");
            config.getAsJsonArray("tradebooks").get(0).getAsJsonObject().add("entryMethods", com.google.gson.JsonParser.parseString("[\"1 R\"]"));
            server.acceptLocalMessage(config);
            assertEquals(3, radios(panel).size());
            assertSelection(panel, "1 R");
            assertSelection(rebuilt, "1 R");
            assertThrows(IllegalArgumentException.class, () -> selection.setEntryMethod("AAPL", "100 sh"));
            assertEquals(0, dispatches.get());
            panel.dispose(); rebuilt.dispose();
        });
    }

    private List<JRadioButton> radios(HotkeyRiskPanel panel) {
        return Arrays.stream(panel.getComponents()).filter(JRadioButton.class::isInstance)
                .map(JRadioButton.class::cast).collect(java.util.stream.Collectors.toList());
    }

    private void assertSelection(HotkeyRiskPanel panel, String method) {
        assertEquals(1, radios(panel).stream().filter(JRadioButton::isSelected).count());
        assertEquals(method, radios(panel).stream().filter(JRadioButton::isSelected).findFirst().get().getText());
        assertEquals("Hotkey size: " + method, ((JLabel) panel.getComponent(0)).getText());
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
