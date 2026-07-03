package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.bookmap.plugin.rong.pricelines.PriceZone;
import com.bookmap.plugin.rong.pricelines.PriceZoneStore;

class KeyZoneManagerTest {

    @Test
    void keyZonesDrawWhenBookmapRegistersRawAliasAndBotSendsPureTicker() {
        PriceZoneStore store = new PriceZoneStore();
        KeyZoneManager manager = new KeyZoneManager(store);

        manager.onInstrumentInitialized("META:NASDAQ:STOCKS@BMD", 0.01);
        manager.onKeyZonesChanged("META", Collections.singletonList(
                new KeyZoneDefinition("META", 620.0, 600.0, "base", "#9ca3af")));

        List<PriceZone> zones = store.getZones("META");
        assertEquals(1, zones.size());
        assertTrue(store.getZones("META:NASDAQ:STOCKS@BMD").isEmpty());

        PriceZone zone = zones.get(0);
        assertEquals(600.0, zone.getRealLowPrice(), 0.00001);
        assertEquals(620.0, zone.getRealHighPrice(), 0.00001);
        assertEquals(60000.0, zone.getLowPriceInTicks(), 0.00001);
        assertEquals(62000.0, zone.getHighPriceInTicks(), 0.00001);
        assertEquals("base", zone.getLabel());
        assertEquals(new Color(0x9c, 0xa3, 0xaf), zone.getColor());
    }
}
