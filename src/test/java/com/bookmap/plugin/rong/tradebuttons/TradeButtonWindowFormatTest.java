package com.bookmap.plugin.rong.tradebuttons;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;

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
    void pendingRetestProducesDirectionSpecificWarningWithoutBlockingButtonAction() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        server.onMessage(null, "{"
                + "\"type\":\"key_levels_config\","
                + "\"symbol\":\"AAPL\","
                + "\"waitForBidRetest\":true,"
                + "\"waitForOfferRetest\":true,"
                + "\"levels\":[]"
                + "}");

        SignalWebSocketServer.EntryRetestState state = server.getEntryRetestState("AAPL");
        assertEquals("wait for bid retest", TradeButtonWindow.getRetestWarning(true, state));
        assertEquals("wait for offer retest", TradeButtonWindow.getRetestWarning(false, state));

        server.markEntryRetestSatisfied("AAPL", true);
        state = server.getEntryRetestState("AAPL");
        assertEquals("", TradeButtonWindow.getRetestWarning(true, state));
        assertEquals("wait for offer retest", TradeButtonWindow.getRetestWarning(false, state));
    }
}
