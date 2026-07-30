package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

class BookmapPriceNormalizerTest {

    @Test
    void translatesBothDirectionsUsingPips() {
        double wirePrice = 122.2168978158744;
        double priceLevel = BookmapPriceNormalizer.toBookmapPriceLevel(wirePrice, 0.01);

        assertEquals(12_221.68978158744, priceLevel, 0.0000001);
        assertEquals(
                wirePrice,
                BookmapPriceNormalizer.toWirePrice(priceLevel, 0.01),
                0.0000001);
    }

    @Test
    void rejectsInvalidPricesAndScales() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BookmapPriceNormalizer.toBookmapPriceLevel(122.21, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> BookmapPriceNormalizer.toWirePrice(Double.NaN, 0.01));
    }

    @Test
    void optionalBookmapPriceSamplesReturnNaNInsteadOfThrowing() {
        assertTrue(Double.isNaN(BookmapPriceNormalizer.toWirePriceOrNaN(0, 0.01)));
        assertTrue(Double.isNaN(BookmapPriceNormalizer.toWirePriceOrNaN(-1, 0.01)));
        assertTrue(Double.isNaN(
                BookmapPriceNormalizer.toWirePriceOrNaN(Double.NaN, 0.01)));
        assertTrue(Double.isNaN(
                BookmapPriceNormalizer.toWirePriceOrNaN(Double.POSITIVE_INFINITY, 0.01)));
        assertTrue(Double.isNaN(BookmapPriceNormalizer.toWirePriceOrNaN(12_221, 0)));
        assertTrue(Double.isNaN(
                BookmapPriceNormalizer.toWirePriceOrNaN(Double.MAX_VALUE, 2)));

        assertEquals(
                122.21,
                BookmapPriceNormalizer.toWirePriceOrNaN(12_221, 0.01),
                0.0000001);
    }

    @Test
    void wireContractUsesRealPricesAndAcceptsLegacyMissingMarker() {
        JsonObject json = new JsonObject();
        BookmapPriceNormalizer.addWirePriceUnit(json);

        assertEquals(
                BookmapPriceNormalizer.WIRE_PRICE_UNIT,
                json.get(BookmapPriceNormalizer.WIRE_PRICE_UNIT_FIELD).getAsString());
        assertTrue(BookmapPriceNormalizer.isSupportedWirePriceUnit("real"));
        assertTrue(BookmapPriceNormalizer.isSupportedWirePriceUnit(""));
        assertFalse(BookmapPriceNormalizer.isSupportedWirePriceUnit("ticks"));
    }
}
