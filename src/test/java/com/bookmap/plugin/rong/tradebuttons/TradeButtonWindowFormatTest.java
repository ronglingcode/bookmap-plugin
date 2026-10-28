package com.bookmap.plugin.rong.tradebuttons;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import javax.swing.JButton;

import org.junit.jupiter.api.Test;

import com.bookmap.plugin.rong.SignalWebSocketServer;

class TradeButtonWindowFormatTest {
    @Test void riskAndShareButtonsUseTwoColumnsAcrossMoreRows() {
        var panel = new javax.swing.JPanel(new java.awt.GridLayout(0, 1, 0, 6));
        TradeButtonWindow.appendEntryRows(panel, Arrays.asList(new JButton("1 R"), new JButton("0.5 R"), new JButton("0.3 R"), new JButton("0.2 R"), new JButton("0.1 R")), 2);
        TradeButtonWindow.appendEntryRows(panel, Arrays.asList(new JButton("50 sh"), new JButton("100 sh")), 2);
        panel.setSize(245, 240); panel.doLayout();
        assertEquals(4, panel.getComponentCount());
        assertEquals("0.1 R", ((JButton)((javax.swing.JPanel)panel.getComponent(2)).getComponent(0)).getText());
        assertEquals("50 sh", ((JButton)((javax.swing.JPanel)panel.getComponent(3)).getComponent(0)).getText());
        for (var component : panel.getComponents()) {
            var row = (javax.swing.JPanel) component; row.doLayout();
            assertEquals(2, row.getComponentCount());
            assertEquals(panel.getWidth(), row.getWidth());
            var last = row.getComponent(row.getComponentCount() - 1);
            org.junit.jupiter.api.Assertions.assertTrue(Math.abs(row.getWidth() - last.getX() - last.getWidth()) <= 2);
        }
    }


    @Test
    void formatsLargestSizesCompactlyForThresholdLabel() {
        assertEquals(
                "1.2M/250K/9.5K",
                TradeButtonWindow.formatLargestSizes(Arrays.asList(1_200_000, 250_000, 9_500)));
        assertEquals("n/a", TradeButtonWindow.formatLargestSizes(Collections.emptyList()));
    }

    @Test
    void trackedShiftStateKeepsMarketModeAcrossWindowFocusTransfer() {
        JButton button = new JButton("1 R");
        assertEquals(true, TradeButtonWindow.isMarketOrderAction(null, button, true));
        assertEquals(false, TradeButtonWindow.isMarketOrderAction(null, button, false));
    }

    @Test
    void pendingRetestProducesDirectionSpecificWarningAndHonorsBlockingMode() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        localMessage(server, "{"
                + "\"type\":\"key_levels_config\","
                + "\"symbol\":\"AAPL\","
                + "\"waitForBidRetest\":\"yes\","
                + "\"waitForOfferRetest\":\"warning\","
                + "\"levels\":[]"
                + "}");

        SignalWebSocketServer.EntryRetestState state = server.getEntryRetestState("AAPL");
        assertEquals("wait for bid retest", TradeButtonWindow.getRetestWarning(true, state));
        assertEquals("wait for offer retest", TradeButtonWindow.getRetestWarning(false, state));
        assertEquals(true, TradeButtonWindow.isRetestBlocked(true, state));
        assertEquals(false, TradeButtonWindow.isRetestBlocked(false, state));

        server.markEntryRetestSatisfied("AAPL", true);
        state = server.getEntryRetestState("AAPL");
        assertEquals("", TradeButtonWindow.getRetestWarning(true, state));
        assertEquals("wait for offer retest", TradeButtonWindow.getRetestWarning(false, state));
        assertEquals(false, TradeButtonWindow.isRetestBlocked(true, state));
    }
    private static void localMessage(com.bookmap.plugin.rong.SignalWebSocketServer server, String json) { server.acceptLocalMessage(com.google.gson.JsonParser.parseString(json).getAsJsonObject()); }
}
