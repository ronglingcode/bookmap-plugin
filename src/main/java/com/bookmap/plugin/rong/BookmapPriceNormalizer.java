package com.bookmap.plugin.rong;

import com.google.gson.JsonObject;

/**
 * Defines the price-unit boundary between Bookmap and ViteApp.
 *
 * <p>Every price sent over the WebSocket is a real instrument price (for
 * example, 122.21 USD). Bookmap's Layer 1 callbacks and primary-chart APIs use
 * price levels, where {@code realPrice = priceLevel * pips}. All translation
 * between those representations must go through this class.</p>
 */
public final class BookmapPriceNormalizer {

    public static final String WIRE_PRICE_UNIT_FIELD = "priceUnit";
    public static final String WIRE_PRICE_UNIT = "real";

    private BookmapPriceNormalizer() {}

    public static boolean isValidWirePrice(double price) {
        return Double.isFinite(price) && price > 0;
    }

    public static double normalizeWirePrice(double price) {
        return isValidWirePrice(price) ? price : Double.NaN;
    }

    public static double toWirePrice(double bookmapPriceLevel, double pips) {
        requirePositiveFinite(bookmapPriceLevel, "bookmapPriceLevel");
        requirePositiveFinite(pips, "pips");
        return bookmapPriceLevel * pips;
    }

    /**
     * Converts an optional Bookmap price sample without throwing.
     *
     * <p>Bookmap UI callbacks can temporarily expose an uninitialized price
     * level while chart coordinates are changing. Callers that are sampling
     * optional UI state should discard those values rather than fail the AWT
     * event dispatch thread. Business-data paths should continue to use
     * {@link #toWirePrice(double, double)} so invalid prices fail fast.</p>
     */
    public static double toWirePriceOrNaN(double bookmapPriceLevel, double pips) {
        if (!isPositiveFinite(bookmapPriceLevel) || !isPositiveFinite(pips)) {
            return Double.NaN;
        }
        return normalizeWirePrice(bookmapPriceLevel * pips);
    }

    public static double toBookmapPriceLevel(double wirePrice, double pips) {
        requirePositiveFinite(wirePrice, "wirePrice");
        requirePositiveFinite(pips, "pips");
        return wirePrice / pips;
    }

    public static boolean isSupportedWirePriceUnit(String priceUnit) {
        return priceUnit == null || priceUnit.trim().isEmpty()
                || WIRE_PRICE_UNIT.equalsIgnoreCase(priceUnit.trim());
    }

    public static void addWirePriceUnit(JsonObject json) {
        if (json != null) {
            json.addProperty(WIRE_PRICE_UNIT_FIELD, WIRE_PRICE_UNIT);
        }
    }

    private static void requirePositiveFinite(double value, String name) {
        if (!isPositiveFinite(value)) {
            throw new IllegalArgumentException(name + " must be positive and finite");
        }
    }

    private static boolean isPositiveFinite(double value) {
        return Double.isFinite(value) && value > 0;
    }
}
