package com.bookmap.plugin.rong.tradebuttons;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import javax.swing.JButton;

import org.junit.jupiter.api.Test;

import com.bookmap.plugin.rong.SignalWebSocketServer;

class TradeButtonWindowFormatTest {

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
