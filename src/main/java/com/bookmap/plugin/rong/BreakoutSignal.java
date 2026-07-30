package com.bookmap.plugin.rong;

import java.util.Locale;

public class BreakoutSignal {

    public final String symbol;
    public final double breakoutLevel;
    public final long timestamp;

    public BreakoutSignal(String symbol, double breakoutLevel) {
        this.symbol = symbol;
        this.breakoutLevel = BookmapPriceNormalizer.normalizeWirePrice(breakoutLevel);
        if (!Double.isFinite(this.breakoutLevel)) {
            throw new IllegalArgumentException("breakoutLevel must be a valid wire price");
        }
        this.timestamp = System.currentTimeMillis();
    }

    public String toJson() {
        return String.format(
            Locale.US,
            "{\"type\":\"breakout\",\"symbol\":\"%s\",\"priceUnit\":\"%s\","
                    + "\"breakoutLevel\":%.6f,\"timestamp\":%d}",
            symbol, BookmapPriceNormalizer.WIRE_PRICE_UNIT, breakoutLevel, timestamp);
    }
}
