package com.bookmap.plugin.rong.tradebuttons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class HotkeyButtonActionTest {

    @Test
    void cancelMessageMatchesCancelButtonPayloadWithoutPrice() {
        JsonObject json = HotkeyButtonAction.createMessage(
                "AAPL", "cancel", "Cancel", "KeyC", false, 123L);

        assertEquals("custom_button_click", json.get("type").getAsString());
        assertEquals("AAPL", json.get("symbol").getAsString());
        assertEquals("hotkey:cancel", json.get("button_id").getAsString());
        assertEquals("Cancel", json.get("button_name").getAsString());
        assertEquals("KeyC", json.get("keyCode").getAsString());
        assertEquals("KeyC", json.get("key_code").getAsString());
        assertFalse(json.get("shiftKey").getAsBoolean());
        assertFalse(json.get("shift_key").getAsBoolean());
        assertEquals(123L, json.get("timestamp").getAsLong());
        assertFalse(json.has("price"));
    }

    @Test
    void flattenMessageMatchesFlattenButtonPayloadWithoutPrice() {
        JsonObject json = HotkeyButtonAction.createMessage(
                "NVDA", "flatten", "Flatten", "KeyF", false, 456L);

        assertEquals("NVDA", json.get("symbol").getAsString());
        assertEquals("hotkey:flatten", json.get("button_id").getAsString());
        assertEquals("Flatten", json.get("button_name").getAsString());
        assertEquals("KeyF", json.get("keyCode").getAsString());
        assertFalse(json.has("price"));
    }

    @Test
    void swapMessageMatchesSwapButtonPayloadWithoutPrice() {
        JsonObject json = HotkeyButtonAction.createMessage(
                "TSLA", "swap", "Swap", "KeyW", false, 789L);

        assertEquals("TSLA", json.get("symbol").getAsString());
        assertEquals("hotkey:swap", json.get("button_id").getAsString());
        assertEquals("Swap", json.get("button_name").getAsString());
        assertEquals("KeyW", json.get("keyCode").getAsString());
        assertFalse(json.has("price"));
    }
}
