package com.bookmap.plugin.rong.pricelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Canvas;
import java.awt.event.KeyEvent;

import org.junit.jupiter.api.Test;

class ChartClickHandlerTest {

    @Test
    void numpadDigitsMapToViteNumpadHotkeys() {
        for (int digit = 1; digit <= 9; digit++) {
            String normalizedKey = "numpad" + digit;

            assertEquals(
                    normalizedKey,
                    ChartClickHandler.normalizeKey(keyPressed(KeyEvent.VK_NUMPAD0 + digit)));
            assertTrue(ChartClickHandler.isChartHotkey(normalizedKey));
            assertEquals("Numpad" + digit, ChartClickHandler.toViteKeyCode(normalizedKey));
        }
    }

    @Test
    void topRowDigitsRemainViteDigitHotkeys() {
        assertEquals("1", ChartClickHandler.normalizeKey(keyPressed(KeyEvent.VK_1)));
        assertEquals("Digit1", ChartClickHandler.toViteKeyCode("1"));
        assertEquals("Digit9", ChartClickHandler.toViteKeyCode("9"));
    }

    @Test
    void hoverHotkeyActionLogContainsEveryRequiredField() {
        assertEquals(
                "hover_key AAPL Numpad3 @ 12.35 + shift",
                ChartClickHandler.formatHoverHotkeyActionLog(
                        "AAPL", "Numpad3", 12.3456, true));
        assertEquals(
                "hover_key NVDA Digit1 @ 171.25",
                ChartClickHandler.formatHoverHotkeyActionLog(
                        "NVDA", "Digit1", 171.25, false));
    }

    private static KeyEvent keyPressed(int keyCode) {
        return new KeyEvent(
                new Canvas(),
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                0,
                keyCode,
                KeyEvent.CHAR_UNDEFINED);
    }
}
