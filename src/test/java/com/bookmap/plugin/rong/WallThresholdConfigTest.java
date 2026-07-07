package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WallThresholdConfigTest {

    @Test
    void defaultsThresholdFloorToFiveThousand() {
        WallThresholdConfig config = new WallThresholdConfig();

        assertEquals(5_000, config.getThresholdFloor());
    }

    @Test
    void clampsThresholdFloorToSupportedRange() {
        WallThresholdConfig config = new WallThresholdConfig();

        config.setThresholdFloor(-1);
        assertEquals(0, config.getThresholdFloor());

        config.setThresholdFloor(WallThresholdConfig.MAX_THRESHOLD_FLOOR + 1);
        assertEquals(WallThresholdConfig.MAX_THRESHOLD_FLOOR, config.getThresholdFloor());
    }
}
