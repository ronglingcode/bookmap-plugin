package com.bookmap.plugin.rong.pricelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Canvas;
import java.awt.event.KeyEvent;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class ChartHoverHotkeyHandlerTest {

    @Test
    void numpadDigitsMapToViteNumpadHotkeys() {
        for (int digit = 1; digit <= 9; digit++) {
            String normalizedKey = "numpad" + digit;

            assertEquals(
                    normalizedKey,
                    ChartHoverHotkeyHandler.normalizeKey(keyPressed(KeyEvent.VK_NUMPAD0 + digit)));
            assertTrue(ChartHoverHotkeyHandler.isChartHotkey(normalizedKey));
            assertEquals("Numpad" + digit, ChartHoverHotkeyHandler.toViteKeyCode(normalizedKey));
        }
    }

    @Test
    void topRowDigitsRemainViteDigitHotkeys() {
        assertEquals("1", ChartHoverHotkeyHandler.normalizeKey(keyPressed(KeyEvent.VK_1)));
        assertEquals("Digit1", ChartHoverHotkeyHandler.toViteKeyCode("1"));
        assertEquals("Digit9", ChartHoverHotkeyHandler.toViteKeyCode("9"));
    }

    @Test
    void buyAndSellKeysMapToWallReversalHotkeys() {
        assertTrue(ChartHoverHotkeyHandler.isChartHotkey("b"));
        assertTrue(ChartHoverHotkeyHandler.isWallReversalHotkey("b"));
        assertEquals("KeyB", ChartHoverHotkeyHandler.toViteKeyCode("b"));

        assertTrue(ChartHoverHotkeyHandler.isChartHotkey("s"));
        assertTrue(ChartHoverHotkeyHandler.isWallReversalHotkey("s"));
        assertEquals("KeyS", ChartHoverHotkeyHandler.toViteKeyCode("s"));
    }

    @Test
    void buttonEquivalentHotkeysDoNotRequireHoveredPrices() {
        assertTrue(ChartHoverHotkeyHandler.isChartHotkey("c"));
        assertTrue(ChartHoverHotkeyHandler.isPriceIndependentHotkey("c"));
        assertEquals("KeyC", ChartHoverHotkeyHandler.toViteKeyCode("c"));

        assertTrue(ChartHoverHotkeyHandler.isChartHotkey("f"));
        assertTrue(ChartHoverHotkeyHandler.isPriceIndependentHotkey("f"));
        assertEquals("KeyF", ChartHoverHotkeyHandler.toViteKeyCode("f"));

        assertTrue(ChartHoverHotkeyHandler.isChartHotkey("w"));
        assertTrue(ChartHoverHotkeyHandler.isPriceIndependentHotkey("w"));
        assertEquals("KeyW", ChartHoverHotkeyHandler.toViteKeyCode("w"));

        assertFalse(ChartHoverHotkeyHandler.isPriceIndependentHotkey("b"));
        assertFalse(ChartHoverHotkeyHandler.isPriceIndependentHotkey("1"));
    }

    @Test
    void hoverHotkeyActionLogContainsEveryRequiredField() {
        assertEquals(
                "hover_key AAPL Numpad3 @ 12.35 + shift",
                ChartHoverHotkeyHandler.formatHoverHotkeyActionLog(
                        "AAPL", "Numpad3", 12.3456, true));
        assertEquals(
                "hover_key NVDA Digit1 @ 171.25",
                ChartHoverHotkeyHandler.formatHoverHotkeyActionLog(
                        "NVDA", "Digit1", 171.25, false));
    }

    @Test
    void entryHotkeysStopSendingAtTenAmNewYorkTime() {
        assertFalse(ChartHoverHotkeyHandler.isEntryHotkeyDisabledAt(
                "b", Instant.parse("2026-07-24T13:59:59Z")));
        assertTrue(ChartHoverHotkeyHandler.isEntryHotkeyDisabledAt(
                "b", Instant.parse("2026-07-24T14:00:00Z")));

        assertFalse(ChartHoverHotkeyHandler.isEntryHotkeyDisabledAt(
                "s", Instant.parse("2026-01-15T14:59:59Z")));
        assertTrue(ChartHoverHotkeyHandler.isEntryHotkeyDisabledAt(
                "s", Instant.parse("2026-01-15T15:00:00Z")));
    }

    @Test
    void exitAndManagementHotkeysRemainEnabledAfterEntryCutoff() {
        Instant afterMarketClose = Instant.parse("2026-07-24T21:00:00Z");

        for (String key : new String[] {
                "a", "c", "f", "g", "t", "w", "1", "0", "numpad1", "numpad0"
        }) {
            assertFalse(
                    ChartHoverHotkeyHandler.isEntryHotkeyDisabledAt(key, afterMarketClose),
                    key);
        }
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
