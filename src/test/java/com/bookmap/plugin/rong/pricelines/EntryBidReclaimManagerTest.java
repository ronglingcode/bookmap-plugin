package com.bookmap.plugin.rong.pricelines;

import java.awt.Color;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.SignalWebSocketServer;
import com.bookmap.plugin.rong.OrderBookState;
import com.google.gson.JsonParser;
import static org.junit.jupiter.api.Assertions.*;

class EntryBidReclaimManagerTest {
    @Test void serverConnectsNativeViewToBookmapLevelOneBidAndKeepsWireUnitsExplicit() {
        PriceLineStore store = new PriceLineStore();
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        server.setEntryBidReclaimStore(store);
        server.registerSymbol("AAPL", new OrderBookState(), .01);
        var config = JsonParser.parseString("{\"type\":\"entry_bid_reclaim_config\",\"priceUnit\":\"real\",\"symbol\":\"AAPL\",\"isShort\":true,\"entryBidToReclaim\":10,\"averagePrice\":10.1}").getAsJsonObject();
        server.acceptLocalMessage(config);
        server.observeSessionBbo("AAPL", 999, 1001, 1);
        assertFalse(store.getLines("AAPL").get(0).getCustomLabel().contains("RECLAIMED"), "Ask above level is insufficient");
        server.observeSessionBbo("AAPL", 1000, 1001, 2);
        assertTrue(store.getLines("AAPL").get(0).getCustomLabel().contains("RECLAIMED"));
        config.addProperty("priceUnit", "ticks"); config.addProperty("entryBidToReclaim", 1000);
        server.acceptLocalMessage(config);
        assertEquals(10, store.getLines("AAPL").get(0).getRealPrice());
        server.unregisterSymbol("AAPL"); assertTrue(store.getLines("AAPL").isEmpty());
    }
    @Test void reminderMarkerReclaimAndPositionLifecycleAreIsolatedBySymbol() {
        PriceLineStore store = new PriceLineStore();
        EntryBidReclaimManager manager = new EntryBidReclaimManager(store);
        manager.register("AAPL", .01); manager.register("MSFT", .25);
        manager.update("AAPL", true, 0, 10);
        assertTrue(store.getLines("AAPL").get(0).getCustomLabel().contains("SET ENTRY BID"));
        manager.onBid("AAPL", 9.8);
        manager.update("AAPL", true, 9.9, 10);
        assertEquals(990, store.getLines("AAPL").get(0).getPriceInTicks(), .00001);
        assertFalse(store.getLines("AAPL").get(0).getCustomLabel().contains("RECLAIMED"));
        manager.onBid("AAPL", Double.NaN); manager.onBid("MSFT", 11); manager.onBid("AAPL", 9.89);
        assertFalse(store.getLines("AAPL").get(0).getCustomLabel().contains("RECLAIMED"));
        manager.onBid("AAPL", 9.9);
        assertTrue(store.getLines("AAPL").get(0).getCustomLabel().contains("RECLAIMED: STOP OUT"));
        assertEquals(new Color(255, 55, 65), store.getLines("AAPL").get(0).getCustomColor());
        manager.onBid("AAPL", 9.8); manager.update("AAPL", true, 9.9, 10);
        assertTrue(store.getLines("AAPL").get(0).getCustomLabel().contains("RECLAIMED"), "Reclaim stays latched");
        manager.update("AAPL", true, 10.1, 10);
        assertEquals(1, store.getLines("AAPL").size());
        assertFalse(store.getLines("AAPL").get(0).getCustomLabel().contains("RECLAIMED"));
        manager.update("AAPL", true, 0, 10);
        assertTrue(store.getLines("AAPL").get(0).getCustomLabel().contains("SET ENTRY BID"));
        manager.update("AAPL", false, 0, 0);
        assertTrue(store.getLines("AAPL").isEmpty());
        manager.update("MSFT", true, 20, 21);
        assertEquals(80, store.getLines("MSFT").get(0).getPriceInTicks());
        manager.unregister("MSFT"); assertTrue(store.getLines("MSFT").isEmpty());
    }

    @Test void settingAtAnAlreadyReclaimedBidWarnsImmediatelyAndPipsCanArriveLater() {
        PriceLineStore store = new PriceLineStore(); EntryBidReclaimManager manager = new EntryBidReclaimManager(store);
        manager.update("AAPL", true, 10, 10.1);
        assertTrue(store.getLines("AAPL").isEmpty());
        manager.register("AAPL", .01); manager.onBid("AAPL", 10);
        manager.update("AAPL", true, 9.99, 10.1);
        assertTrue(store.getLines("AAPL").get(0).getCustomLabel().contains("RECLAIMED"));
    }
}
