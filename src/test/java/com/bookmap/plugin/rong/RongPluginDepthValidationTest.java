package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.SignalComposerActivationTest.*;

import org.junit.jupiter.api.Test;

class RongPluginDepthValidationTest {
    @Test
    void invalidPricesNeverEnterBookAndValidLevelRemovalsStillWork() throws Exception {
        withIsolatedActivation(() -> {
            RongPlugin plugin = SignalComposerCallbacksTest.callbackPlugin();
            OrderBookState book = (OrderBookState) field("orderBook").get(plugin);
            for (boolean bid : new boolean[]{true, false}) {
                plugin.onDepth(bid, 10_000, 8_000);
                for (int price : new int[]{0, -1, Integer.MIN_VALUE}) {
                    plugin.onDepth(bid, price, 16_000);
                    plugin.onDepth(bid, price, 0);
                    plugin.onDepth(bid, price, 16_000);
                }
                assertEquals(1, book.getLevelsSnapshot(bid).size());
                assertEquals(8_000, book.getSizeAt(bid, 10_000));
                plugin.onDepth(bid, 10_000, 0);
                assertTrue(book.getLevelsSnapshot(bid).isEmpty());
            }
        });
    }
}
