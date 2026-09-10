package com.bookmap.plugin.rong.tradebuttons;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

class TradeButtonWindowFormatTest {

    @Test
    void formatsLargestSizesCompactlyForThresholdLabel() {
        assertEquals(
                "1.2M/250K/9.5K",
                TradeButtonWindow.formatLargestSizes(Arrays.asList(1_200_000, 250_000, 9_500)));
        assertEquals("n/a", TradeButtonWindow.formatLargestSizes(Collections.emptyList()));
    }
}
