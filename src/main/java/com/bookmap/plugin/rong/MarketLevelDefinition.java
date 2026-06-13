package com.bookmap.plugin.rong;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Market levels supplied by the external websocket client for one instrument.
 */
public class MarketLevelDefinition {

    private final String symbol;
    private final Map<String, Double> camPivots;
    private final double previousDayHigh;
    private final double previousDayLow;
    private final double premarketHigh;
    private final double premarketLow;

    public MarketLevelDefinition(
            String symbol,
            Map<String, Double> camPivots,
            double previousDayHigh,
            double previousDayLow,
            double premarketHigh,
            double premarketLow) {
        this.symbol = SymbolUtils.cleanSymbol(symbol);
        this.camPivots = Collections.unmodifiableMap(new LinkedHashMap<>(camPivots));
        this.previousDayHigh = previousDayHigh;
        this.previousDayLow = previousDayLow;
        this.premarketHigh = premarketHigh;
        this.premarketLow = premarketLow;
    }

    public String getSymbol() {
        return symbol;
    }

    public Map<String, Double> getCamPivots() {
        return camPivots;
    }

    public double getPreviousDayHigh() {
        return previousDayHigh;
    }

    public double getPreviousDayLow() {
        return previousDayLow;
    }

    public double getPremarketHigh() {
        return premarketHigh;
    }

    public double getPremarketLow() {
        return premarketLow;
    }
}
