package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.controllers.HoverShareEntry;
import com.bookmap.plugin.rong.miniviteapp.models.Models.Snapshot;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HoverShareEntryTest {
    private JsonObject state() {
        JsonObject value = JsonParser.parseReader(new InputStreamReader(getClass().getResourceAsStream("/direct-entry-fixtures.json"), StandardCharsets.UTF_8))
                .getAsJsonArray().get(0).getAsJsonObject().getAsJsonObject("state").deepCopy();
        value.addProperty("bid", 9.99); value.addProperty("ask", 10.01);
        value.getAsJsonObject("entryContext").add("fixedShares", JsonParser.parseString("[500,1000,3000,5000,7000]"));
        return value;
    }
    private JsonObject action(double stop) {
        JsonObject value = new JsonObject(); value.addProperty("source", "bookmap_chart_hotkey"); value.addProperty("price", stop); return value;
    }
    @Test void mapsOnlyShareButtonsInTheirConfiguredOrderAndInfersSideFromStop() {
        for (boolean isLong : new boolean[]{true, false}) for (int i = 1; i <= 5; i++) {
            JsonObject entry = HoverShareEntry.prepare(new Snapshot(state()), action(isLong ? 9.5 : 10.5), "Digit" + i);
            assertEquals(new String[]{"500 sh", "1000 sh", "3000 sh", "5000 sh", "7000 sh"}[i - 1], entry.get("entry_method").getAsString());
            assertEquals(isLong, entry.get("sideIsLong").getAsBoolean()); assertTrue(entry.get("use_market_order").getAsBoolean());
            assertEquals(isLong ? 9.5 : 10.5, entry.get("hoverStopPrice").getAsDouble());
        }
    }
    @Test void missingButtonSpreadStopPendingOrdersAndDisabledDirectionBlock() {
        JsonObject state = state(); state.getAsJsonObject("entryContext").add("fixedShares", JsonParser.parseString("[500,1000]"));
        assertThrows(IllegalArgumentException.class, () -> HoverShareEntry.prepare(new Snapshot(state), action(9.5), "Digit3"));
        for (double stop : new double[]{9.99, 10, 10.01, 0})
            assertThrows(IllegalArgumentException.class, () -> HoverShareEntry.prepare(new Snapshot(state()), action(stop), "Digit1"));
        state.add("entries", JsonParser.parseString("[{\"orderID\":\"1\",\"isBuy\":true,\"quantity\":500,\"price\":10,\"orderType\":\"LIMIT\"}]"));
        assertThrows(IllegalArgumentException.class, () -> HoverShareEntry.prepare(new Snapshot(state), action(9.5), "Digit1"));
        JsonObject disabled = state();
        for (var definition : disabled.getAsJsonObject("entryContext").getAsJsonArray("definitions")) definition.getAsJsonObject().addProperty("enabled", false);
        assertThrows(IllegalArgumentException.class, () -> HoverShareEntry.prepare(new Snapshot(disabled), action(9.5), "Digit1"));
    }
    @Test void shortcutOnlyRecognizesChartTopRowOneThroughFive() {
        assertTrue(HoverShareEntry.isChartDigit(action(9.5), "Digit1"));
        assertFalse(HoverShareEntry.isChartDigit(action(9.5), "Numpad1"));
        assertFalse(HoverShareEntry.isChartDigit(action(9.5), "Digit6"));
        assertFalse(HoverShareEntry.isChartDigit(new JsonObject(), "Digit1"));
    }
}
