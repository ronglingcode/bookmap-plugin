package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class NewPositionConfigParsingTest {

    @Test
    void validNewPositionIsDeliveredToMatchingSymbolListener() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90, 1000);
        AtomicReference<NewPositionDefinition> positionRef = new AtomicReference<>();
        server.registerNewPositionListener("AAPL", positionRef::set);

        server.onMessage(null, newPositionJson(100, true));

        NewPositionDefinition position = positionRef.get();
        assertEquals("AAPL", position.getSymbol());
        assertTrue(position.isLongPosition());
        assertEquals(100, position.getNetQuantity(), 0.00001);
        assertEquals(110.25, position.getAveragePrice(), 0.00001);
        assertEquals("AAPL:long:1785243960000", position.getEventId());
    }

    @Test
    void zeroQuantityIsRejected() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90, 1000);
        AtomicReference<NewPositionDefinition> positionRef = new AtomicReference<>();
        server.registerNewPositionListener("AAPL", positionRef::set);

        server.onMessage(null, newPositionJson(0, true));

        assertNull(positionRef.get());
    }

    @Test
    void mismatchedSideIsRejected() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90, 1000);
        AtomicReference<NewPositionDefinition> positionRef = new AtomicReference<>();
        server.registerNewPositionListener("AAPL", positionRef::set);

        server.onMessage(null, newPositionJson(-100, true));

        assertNull(positionRef.get());
    }

    private static String newPositionJson(double quantity, boolean isLong) {
        return "{"
                + "\"type\":\"new_position\","
                + "\"priceUnit\":\"real\","
                + "\"symbol\":\"AAPL\","
                + "\"isLong\":" + isLong + ","
                + "\"netQuantity\":" + quantity + ","
                + "\"averagePrice\":110.25,"
                + "\"eventId\":\"AAPL:long:1785243960000\","
                + "\"timestamp\":1785243960000"
                + "}";
    }
}
