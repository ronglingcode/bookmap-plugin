package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import velox.api.layer1.messages.indicators.AliasFilter;

class RongPluginAliasFilterTest {

    @Test
    void screenSpacePainterIsLimitedToItsExactBookmapAlias() {
        AliasFilter filter = RongPlugin.exactAliasFilter("AAPL:NASDAQ:STOCKS@BMD");

        assertTrue(filter.isDisplayedForAlias("AAPL:NASDAQ:STOCKS@BMD"));
        assertFalse(filter.isDisplayedForAlias("MSFT:NASDAQ:STOCKS@BMD"));
        assertFalse(filter.isDisplayedForAlias("AAPL"));
    }
}
