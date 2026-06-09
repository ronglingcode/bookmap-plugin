package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.bookmap.plugin.rong.pricelines.PriceLine;
import com.bookmap.plugin.rong.pricelines.PriceLineStore;

class SymbolNormalizationManagerTest {

    @Test
    void exitOrdersDrawWhenBookmapRegistersRawAliasAndBotSendsPureTicker() {
        PriceLineStore store = new PriceLineStore();
        ExitOrderManager manager = new ExitOrderManager(store);

        manager.onInstrumentInitialized("MSFT:NASDAQ:STOCKS@BMD", 0.01);
        manager.onExitOrderPairsChanged("MSFT", Collections.singletonList(
                new ExitOrderPairDefinition(
                        "MSFT",
                        1,
                        "bot",
                        "parent-1",
                        new ExitOrderLegDefinition("stop-1", 399.25, 10, false),
                        new ExitOrderLegDefinition("limit-1", 405.50, 10, true))));

        List<PriceLine> lines = store.getLines("MSFT");
        assertEquals(2, lines.size());
        assertTrue(store.getLines("MSFT:NASDAQ:STOCKS@BMD").isEmpty());

        PriceLine stopLine = lines.get(0);
        assertEquals(PriceLine.LineType.EXIT_ORDER, stopLine.getType());
        assertEquals("1:STOP", stopLine.getCustomLabel());
        assertEquals(399.25, stopLine.getRealPrice(), 0.00001);
        assertEquals(39925.0, stopLine.getPriceInTicks(), 0.00001);

        PriceLine limitLine = lines.get(1);
        assertEquals(PriceLine.LineType.EXIT_ORDER, limitLine.getType());
        assertEquals("1:LIMIT", limitLine.getCustomLabel());
        assertEquals(405.50, limitLine.getRealPrice(), 0.00001);
        assertEquals(40550.0, limitLine.getPriceInTicks(), 0.00001);
    }

    @Test
    void keyLevelsDrawWhenBookmapRegistersRawAliasAndBotSendsPureTicker() {
        PriceLineStore store = new PriceLineStore();
        KeyLevelManager manager = new KeyLevelManager(store);

        manager.onInstrumentInitialized("TSLA:NASDAQ:STOCKS@BMD", 0.01);
        manager.onKeyLevelsChanged("TSLA", Collections.singletonList(
                new KeyLevelDefinition("TSLA", 185.50, "breakout")));

        List<PriceLine> lines = store.getLines("TSLA");
        assertEquals(1, lines.size());
        assertTrue(store.getLines("TSLA:NASDAQ:STOCKS@BMD").isEmpty());

        PriceLine line = lines.get(0);
        assertEquals(PriceLine.LineType.KEY_LEVEL, line.getType());
        assertEquals("breakout", line.getCustomLabel());
        assertEquals(185.50, line.getRealPrice(), 0.00001);
        assertEquals(18550.0, line.getPriceInTicks(), 0.00001);
    }
}
