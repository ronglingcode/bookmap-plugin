package com.bookmap.plugin.rong.miniviteapp.controllers;

import com.bookmap.plugin.rong.miniviteapp.models.Models.Plan;
import com.bookmap.plugin.rong.miniviteapp.models.Models.Snapshot;

/** Mirror: controllers/keyboardHandler.ts. Entry/swap/reload actions never enter this engine. */
public final class KeyboardHandler {
    private KeyboardHandler() { }
    public static boolean supports(String code, boolean shift) {
        return code.equals("KeyC") || code.equals("KeyF") || code.equals("KeyM")
                || code.matches("Numpad[0-9]") || code.matches("Digit[0-9]")
                || code.equals("KeyG") || code.equals("KeyH") || code.equals("KeyT");
    }
    public static Plan handleKeyPressed(Snapshot state, String code, boolean shift, double price) {
        if (Double.isFinite(price)) price = Math.round(price * 100) / 100.0;
        if (code.equals("KeyC")) return Handler.cancelKeyPressed(state);
        if (code.equals("KeyF")) return Handler.flattenPostionKeyPressed(state);
        if (code.equals("KeyM") || code.startsWith("Numpad")) return Handler.numberPadPressed(state, code);
        if (code.startsWith("Digit")) return Handler.numberKeyPressedAtPrice(state, code, price);
        if ((code.equals("KeyG") || code.equals("KeyH")) && shift) return Handler.keyGPressedWithShift(state);
        return Handler.adjustBatchExitsAtPrice(state, code, price);
    }
}
