package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SymbolUtilsTest {

    @Test
    void cleansBookmapAliasesToPureTicker() {
        assertEquals("MSFT", SymbolUtils.cleanSymbol("MSFT:NASDAQ:STOCKS@BMD"));
        assertEquals("TSLA", SymbolUtils.cleanSymbol("TSLA@BMD"));
        assertEquals("NVDA", SymbolUtils.cleanSymbol("  NVDA:NASDAQ:STOCKS@BMD  "));
    }

    @Test
    void keepsPureTickerAndHandlesMissingValues() {
        assertEquals("MSFT", SymbolUtils.cleanSymbol("MSFT"));
        assertEquals("", SymbolUtils.cleanSymbol(""));
        assertEquals("", SymbolUtils.cleanSymbol(null));
    }
}
