package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.bookmap.plugin.rong.tradebuttons.TradebookButtonGroup;

class MarketEntryEstimateTest {

    @Test
    void longUsesBestAskAndShortUsesBestBid() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        OrderBookState orderBook = new OrderBookState();
        orderBook.update(true, 14_434, 100);
        orderBook.update(false, 14_435, 100);
        server.registerSymbol("PLTR", orderBook, 0.01);

        assertEquals(144.35, server.getMarketEntryEstimate("PLTR", true), 0.00001);
        assertEquals(144.34, server.getMarketEntryEstimate("PLTR", false), 0.00001);
    }

    @Test
    void unavailableBookReturnsNoEstimate() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);

        assertNull(server.getMarketEntryEstimate("PLTR", true));
    }

    @Test
    void tradebookStoresBooleanSideDirectly() {
        assertEquals(true, tradebook(true).isLong());
        assertEquals(false, tradebook(false).isLong());
    }

    private static TradebookButtonGroup tradebook(boolean sideIsLong) {
        return new TradebookButtonGroup(
                "id", "label", sideIsLong,
                "tradebook-id", "tradebook-name", java.util.List.of("default"));
    }
}
