package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IndicatorConfigTest {

    @Test
    void filteredWallChangeVisualAlertsAndSoundAreEnabledByDefault() {
        IndicatorConfig config = new IndicatorConfig();

        assertTrue(config.areOrderChangeAlertsEnabled());
        assertFalse(config.isEnabled(IndicatorConfig.ORDER_WALL_BREAKOUT_SIGNALS));
        assertTrue(config.isEnabled(IndicatorConfig.ORDER_WALL_CHANGE_SOUND));
        assertTrue(config.isOrderChangeSoundEnabled());
    }

    @Test
    void globalOrderChangeSwitchDisablesSoundWithoutLosingSoundPreference() {
        IndicatorConfig config = new IndicatorConfig();

        config.setEnabled(IndicatorConfig.ORDER_WALL_CHANGE_ALERTS, false);

        assertFalse(config.areOrderChangeAlertsEnabled());
        assertFalse(config.isOrderChangeSoundEnabled());
        assertTrue(config.isEnabled(IndicatorConfig.ORDER_WALL_CHANGE_SOUND));

        config.setEnabled(IndicatorConfig.ORDER_WALL_CHANGE_ALERTS, true);
        assertTrue(config.isOrderChangeSoundEnabled());

        config.setEnabled(IndicatorConfig.ORDER_WALL_CHANGE_SOUND, false);
        assertFalse(config.isOrderChangeSoundEnabled());
    }

    @Test
    void wallBreakoutSignalsAreIndependentlyControllable() {
        IndicatorConfig config = new IndicatorConfig();

        config.setEnabled(IndicatorConfig.ORDER_WALL_CHANGE_ALERTS, true);
        assertFalse(config.isEnabled(IndicatorConfig.ORDER_WALL_BREAKOUT_SIGNALS));
        config.setEnabled(IndicatorConfig.ORDER_WALL_BREAKOUT_SIGNALS, true);
        assertTrue(config.isEnabled(IndicatorConfig.ORDER_WALL_BREAKOUT_SIGNALS));
    }

    @Test
    void bookmapPatternAutomationIsDisabledByDefaultAndControllable() {
        IndicatorConfig config = new IndicatorConfig();

        assertFalse(config.isEnabled(IndicatorConfig.BOOKMAP_PATTERN_SIGNALS));
        config.setEnabled(IndicatorConfig.BOOKMAP_PATTERN_SIGNALS, true);
        assertTrue(config.isEnabled(IndicatorConfig.BOOKMAP_PATTERN_SIGNALS));
        config.setEnabled(IndicatorConfig.BOOKMAP_PATTERN_SIGNALS, false);
        assertFalse(config.isEnabled(IndicatorConfig.BOOKMAP_PATTERN_SIGNALS));
    }

    @Test
    void vwapIsEnabledByDefaultAndControllable() {
        IndicatorConfig config = new IndicatorConfig();

        assertTrue(config.isEnabled(IndicatorConfig.VWAP));
        config.setEnabled(IndicatorConfig.VWAP, false);
        assertFalse(config.isEnabled(IndicatorConfig.VWAP));
    }

    @Test
    void filledExecutionLabelsArePersistentByDefaultAndCanUseTimedMode() {
        IndicatorConfig config = new IndicatorConfig();

        assertTrue(config.isEnabled(IndicatorConfig.FILLED_EXECUTION_MARKERS));
        config.setEnabled(IndicatorConfig.FILLED_EXECUTION_MARKERS, false);
        assertFalse(config.isEnabled(IndicatorConfig.FILLED_EXECUTION_MARKERS));
    }
}
