package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IndicatorConfigTest {

    @Test
    void wallChangeVisualAlertsAreDisabledByDefaultButSoundStaysEnabled() {
        IndicatorConfig config = new IndicatorConfig();

        assertFalse(config.isEnabled(IndicatorConfig.ORDER_WALL_CHANGE_ALERTS));
        assertTrue(config.isEnabled(IndicatorConfig.ORDER_WALL_CHANGE_SOUND));
    }
}
