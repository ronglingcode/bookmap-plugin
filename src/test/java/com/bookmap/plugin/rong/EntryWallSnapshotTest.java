package com.bookmap.plugin.rong;

import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntryWallSnapshotTest {
    @Test void entriesCaptureCurrentDepthUsingCurrentFloorAndRealPrices() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 0);
        OrderBookState book = new OrderBookState();
        book.update(true, 9900, 6000); book.update(false, 10100, 7000);
        book.update(false, 10200, 4000);
        server.registerSymbol("TEST", book, 0.01);
        WallThresholdConfig config = new WallThresholdConfig();
        server.setWallThresholdFloor(config::getThresholdFloor);
        AtomicReference<JsonObject> received = new AtomicReference<>();
        server.setTradingDispatch(received::set);
        JsonObject action = new JsonObject(); action.addProperty("symbol", "TEST"); action.addProperty("keyCode", "KeyB");
        server.dispatchTradingAction(action);
        JsonObject walls = received.get().getAsJsonObject("orderbook");
        assertEquals(5000, walls.get("effectiveWallThreshold").getAsInt());
        assertEquals("real", walls.get("priceUnit").getAsString());
        assertEquals(101, walls.getAsJsonArray("largeAsks").get(0).getAsJsonArray().get(0).getAsDouble());
        assertEquals(1, walls.getAsJsonArray("largeAsks").size());
        assertEquals(99, walls.getAsJsonArray("largeBids").get(0).getAsJsonArray().get(0).getAsDouble());
        config.setThresholdFloor(8000);
        server.dispatchTradingAction(action);
        assertTrue(received.get().getAsJsonObject("orderbook").getAsJsonArray("largeAsks").isEmpty());
        assertEquals(1, walls.getAsJsonArray("largeAsks").size());
        assertFalse(action.has("orderbook"));
    }
    @Test void unavailableDepthDoesNotInventWalls() {
        assertFalse(new SignalWebSocketServer(0, 90).entryWallSnapshot("TEST").has("effectiveWallThreshold"));
    }
}
