package com.bookmap.plugin.rong.tradebuttons;

import com.bookmap.plugin.rong.BookmapPriceNormalizer;
import com.bookmap.plugin.rong.PluginLog;
import com.bookmap.plugin.rong.SignalWebSocketServer;
import com.google.gson.JsonObject;

/**
 * Sends the same WebSocket action used by the floating trade window's hotkey buttons.
 */
public final class HotkeyButtonAction {

    private HotkeyButtonAction() {
    }

    public static void send(
            SignalWebSocketServer server,
            String symbol,
            String buttonId,
            String buttonName,
            String keyCode,
            boolean shiftKey) {
        JsonObject json = createMessage(
                symbol, buttonId, buttonName, keyCode, shiftKey, System.currentTimeMillis());
        server.appendRegularSessionHighLow(symbol, json);
        server.broadcast(json.toString());
        if (!"KeyF".equals(keyCode)) {
            PluginLog.action(symbol, "Button send " + buttonName);
        }
        PluginLog.info("[TradeButton] " + buttonName + " clicked for " + symbol + " as "
                + (shiftKey ? "Shift+" : "") + keyCode);
    }

    static JsonObject createMessage(
            String symbol,
            String buttonId,
            String buttonName,
            String keyCode,
            boolean shiftKey,
            long timestamp) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "custom_button_click");
        BookmapPriceNormalizer.addWirePriceUnit(json);
        json.addProperty("symbol", symbol);
        json.addProperty("button_id", "hotkey:" + buttonId);
        json.addProperty("button_name", buttonName);
        json.addProperty("keyCode", keyCode);
        json.addProperty("key_code", keyCode);
        json.addProperty("shiftKey", shiftKey);
        json.addProperty("shift_key", shiftKey);
        json.addProperty("timestamp", timestamp);
        return json;
    }
}
