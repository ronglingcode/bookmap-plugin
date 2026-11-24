package com.bookmap.plugin.rong.pricelines;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PremarketStopLineManagerTest {
    @Test void eachSymbolKeepsItsOwnLocalStopAndRegularSelectionAndBrokerLinesAreUntouched() {
        PriceLineStore store = new PriceLineStore(); PremarketStopLineManager manager = new PremarketStopLineManager(store);
        manager.register("AMD", .01); manager.register("MSFT", .01);
        store.addLine(new PriceLine("AMD", PriceLine.LineType.SELECTED_STOP, 9500, 95));
        store.addLine(new PriceLine("AMD", PriceLine.LineType.EXIT_ORDER, 9600, 96));
        JsonObject stop = JsonParser.parseString("{\"price\":99,\"status\":\"Monitoring\"}").getAsJsonObject();
        manager.update("AMD", stop); manager.update("MSFT", stop);
        assertEquals(3, store.getLines("AMD").size()); assertEquals(1, store.getLines("MSFT").size());
        PriceLine line = store.getLines("MSFT").get(0); assertTrue(line.getCustomLabel().contains("PM LOCAL STOP"));
        manager.update("MSFT", stop); assertSame(line, store.getLines("MSFT").get(0));
        stop.addProperty("price", 100); manager.update("AMD", stop);
        assertEquals(100, store.getLines("AMD").stream().filter(l -> l.getType() == PriceLine.LineType.PREMARKET_STOP).findFirst().orElseThrow().getRealPrice());
        manager.update("AMD", new JsonObject()); assertEquals(2, store.getLines("AMD").size());
        manager.unregister("MSFT"); assertTrue(store.getLines("MSFT").isEmpty());
    }
}
