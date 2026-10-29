package com.bookmap.plugin.rong;

import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EntryWallSnapshotTest {
    @Test void wallOutUsesFixedInclusiveFiveThousandFloorAndRefreshesOnEveryClick() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 99);
        try {
            OrderBookState book = new OrderBookState();
            book.update(false, 10100, 5000); book.update(false, 10200, 4999);
            book.update(true, 9900, 9000); book.update(false, 0, 10000);
            server.registerSymbol("TEST", book, 0.01);
            server.setWallThresholdFloor(() -> 20000);
            AtomicReference<JsonObject> received = new AtomicReference<>();
            server.setTradingDispatch(received::set);
            JsonObject action = new JsonObject(); action.addProperty("symbol", "TEST");
            action.addProperty("button_id", "hotkey:wall_out_2");
            server.dispatchTradingAction(action);
            JsonObject first = received.get().getAsJsonObject("orderbook");
            assertEquals(0.01, first.get("tickSize").getAsDouble());
            assertEquals(5000, first.get("effectiveWallThreshold").getAsInt());
            assertEquals(1, first.getAsJsonArray("largeAsks").size());
            assertEquals(101, first.getAsJsonArray("largeAsks").get(0).getAsJsonArray().get(0).getAsDouble());
            book.update(false, 10100, 0); book.update(false, 10200, 5000);
            server.dispatchTradingAction(action);
            assertEquals(102, received.get().getAsJsonObject("orderbook").getAsJsonArray("largeAsks").get(0).getAsJsonArray().get(0).getAsDouble());
            assertEquals(101, first.getAsJsonArray("largeAsks").get(0).getAsJsonArray().get(0).getAsDouble());
            assertFalse(action.has("orderbook"));
        } finally { server.shutdown(); }
    }

    @Test void browserDepthRequestRepliesOnlyToRequesterWithoutNativeExecution() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 99);
        try {
            OrderBookState book = new OrderBookState(); book.update(false, 10100, 5000);
            server.registerSymbol("TEST", book, 0.01);
            java.util.List<JsonObject> actions = new java.util.ArrayList<>();
            server.setTradingDispatch(actions::add);
            AtomicReference<JsonObject> reply = new AtomicReference<>();
            var connection = (org.java_websocket.WebSocket) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{org.java_websocket.WebSocket.class}, (proxy, method, args) -> {
                        if (method.getName().equals("send") && args[0] instanceof String)
                            reply.set(com.google.gson.JsonParser.parseString((String) args[0]).getAsJsonObject());
                        return null;
                    });
            server.onMessage(connection, "{\"type\":\"wall_out_snapshot_request\",\"symbol\":\"TEST\",\"requestId\":\"42\"}");
            assertEquals("wall_out_snapshot", reply.get().get("type").getAsString());
            assertEquals("42", reply.get().get("requestId").getAsString());
            assertEquals("TEST", reply.get().get("symbol").getAsString());
            assertEquals(server.wallOutSnapshot("TEST"), reply.get().getAsJsonObject("orderbook"));
            assertTrue(actions.isEmpty());
        } finally { server.shutdown(); }
    }

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
