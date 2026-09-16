package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class KeyLevelConfigParsingTest {

    @Test
    void keyLevelLabelFieldBecomesCustomLabel() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        AtomicReference<String> symbolRef = new AtomicReference<>("");
        AtomicReference<List<KeyLevelDefinition>> levelsRef =
                new AtomicReference<>(Collections.emptyList());

        server.registerKeyLevelConfigListener((symbol, levels) -> {
            symbolRef.set(symbol);
            levelsRef.set(levels);
        });

        server.onMessage(null, "{"
                + "\"type\":\"key_levels_config\","
                + "\"priceUnit\":\"real\","
                + "\"symbol\":\"AAPL\","
                + "\"levels\":[{\"price\":185.5,\"label\":\"daily resistance\"}]"
                + "}");

        assertEquals("AAPL", symbolRef.get());
        assertEquals(1, levelsRef.get().size());
        KeyLevelDefinition level = levelsRef.get().get(0);
        assertEquals(185.5, level.getPrice(), 0.00001);
        assertEquals("daily resistance", level.getLabel());
    }

    @Test
    void rejectsPriceConfigsThatClaimBookmapTickUnits() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        AtomicReference<List<KeyLevelDefinition>> levelsRef =
                new AtomicReference<>(Collections.emptyList());
        server.registerKeyLevelConfigListener((symbol, levels) -> levelsRef.set(levels));

        server.onMessage(null, "{"
                + "\"type\":\"key_levels_config\","
                + "\"priceUnit\":\"ticks\","
                + "\"symbol\":\"AAPL\","
                + "\"levels\":[{\"price\":18550}]"
                + "}");

        assertTrue(levelsRef.get().isEmpty());
    }

    @Test
    void keyZoneFieldsBecomeZoneDefinition() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        AtomicReference<String> symbolRef = new AtomicReference<>("");
        AtomicReference<List<KeyZoneDefinition>> zonesRef =
                new AtomicReference<>(Collections.emptyList());

        server.registerKeyZoneConfigListener((symbol, zones) -> {
            symbolRef.set(symbol);
            zonesRef.set(zones);
        });

        server.onMessage(null, "{"
                + "\"type\":\"key_levels_config\","
                + "\"symbol\":\"AAPL\","
                + "\"levels\":[],"
                + "\"zones\":[{\"low\":620,\"high\":600,\"label\":\"daily zone\",\"color\":\"#9ca3af\"}]"
                + "}");

        assertEquals("AAPL", symbolRef.get());
        assertEquals(1, zonesRef.get().size());
        KeyZoneDefinition zone = zonesRef.get().get(0);
        assertEquals(600.0, zone.getLow(), 0.00001);
        assertEquals(620.0, zone.getHigh(), 0.00001);
        assertEquals("daily zone", zone.getLabel());
        assertEquals("#9ca3af", zone.getColor());
    }

    @Test
    void entryRetestRequirementsAreStoredAndSatisfiedIndependentlyBySymbol() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);

        server.onMessage(null, "{"
                + "\"type\":\"key_levels_config\","
                + "\"symbol\":\"AAPL\","
                + "\"waitForBidRetest\":\"yes\","
                + "\"waitForOfferRetest\":\"warning\","
                + "\"levels\":[]"
                + "}");

        SignalWebSocketServer.EntryRetestState state =
                server.getEntryRetestState("AAPL:NASDAQ:STOCKS@BMD");
        assertTrue(state.isBidRetestPending());
        assertTrue(state.isOfferRetestPending());
        assertEquals(SignalWebSocketServer.EntryRetestMode.YES, state.getBidRetestMode());
        assertEquals(SignalWebSocketServer.EntryRetestMode.WARNING, state.getOfferRetestMode());
        assertTrue(state.isEntryRetestBlocked(true));
        assertFalse(state.isEntryRetestBlocked(false));
        assertFalse(server.getEntryRetestState("MSFT").isBidRetestPending());

        server.markEntryRetestSatisfied("AAPL", true);
        state = server.getEntryRetestState("AAPL");
        assertFalse(state.isBidRetestPending());
        assertTrue(state.isOfferRetestPending());

        // Periodic ViteApp refreshes must not re-arm an already satisfied requirement.
        server.onMessage(null, "{"
                + "\"type\":\"key_levels_config\","
                + "\"symbol\":\"AAPL\","
                + "\"waitForBidRetest\":\"warning\","
                + "\"waitForOfferRetest\":\"warning\","
                + "\"levels\":[]"
                + "}");

        state = server.getEntryRetestState("AAPL");
        assertFalse(state.isBidRetestPending());
        assertTrue(state.isOfferRetestPending());
        assertEquals(SignalWebSocketServer.EntryRetestMode.WARNING, state.getBidRetestMode());

        server.onMessage(null, "{"
                + "\"type\":\"key_levels_config\","
                + "\"symbol\":\"AAPL\","
                + "\"waitForBidRetest\":\"no\","
                + "\"waitForOfferRetest\":\"no\","
                + "\"levels\":[]"
                + "}");

        state = server.getEntryRetestState("AAPL");
        assertFalse(state.isBidRetestPending());
        assertFalse(state.isOfferRetestPending());

        // A later false -> true transition starts a fresh wait.
        server.onMessage(null, "{"
                + "\"type\":\"key_levels_config\","
                + "\"symbol\":\"AAPL\","
                + "\"waitForBidRetest\":\"yes\","
                + "\"waitForOfferRetest\":\"no\","
                + "\"levels\":[]"
                + "}");

        state = server.getEntryRetestState("AAPL");
        assertTrue(state.isBidRetestPending());
        assertFalse(state.isOfferRetestPending());
    }
}
