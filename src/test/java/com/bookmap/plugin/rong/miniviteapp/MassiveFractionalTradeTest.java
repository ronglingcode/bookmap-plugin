package com.bookmap.plugin.rong.miniviteapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.bookmap.plugin.rong.miniviteapp.libraries.massive.Mapper;
import com.bookmap.plugin.rong.miniviteapp.models.Trade;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class MassiveFractionalTradeTest {
    @Test
    void restTradeUsesDecimalSizeWhenLegacyIntegerSizeIsZero() {
        Trade trade = Mapper.mapRestTrade("NVDA", JsonParser.parseString("{"
                + "\"sip_timestamp\":1790947446068937906,\"price\":235.5795,\"size\":0,"
                + "\"decimal_size\":\"0.004265\",\"sequence_number\":1174204,"
                + "\"id\":\"335061\",\"exchange\":4,\"conditions\":[12,37]}").getAsJsonObject());

        assertEquals(0.004265, trade.size, 0.000000001);
    }

    @Test
    void websocketTradeUsesDecimalSizeWhenLegacyIntegerSizeIsZero() {
        Trade trade = Mapper.mapWebSocketTrade(JsonParser.parseString("{"
                + "\"ev\":\"T\",\"sym\":\"NVDA\",\"t\":1790947446068,"
                + "\"p\":235.5795,\"s\":0,\"ds\":\"0.004265\",\"q\":1174204}").getAsJsonObject());

        assertNotNull(trade);
        assertEquals(0.004265, trade.size, 0.000000001);
    }
}
