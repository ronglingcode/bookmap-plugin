package com.bookmap.plugin.rong.orderwall;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class OrderWallLabelPainterTest {

    @Test
    void compactSizePathKeepsPeakVisibleAfterStableDecreases() {
        OrderWallLabel label = new OrderWallLabel(
                "id",
                "TEST",
                false,
                47_000,
                470.00,
                2_000,
                108_000,
                1L,
                2L,
                3L,
                true,
                Arrays.asList(79, 106, 107, 108, 11, 5, 2));

        assertEquals("79 -> 108 -> 5 -> 2", OrderWallLabelPainter.formatSizePath(label));
    }
}
