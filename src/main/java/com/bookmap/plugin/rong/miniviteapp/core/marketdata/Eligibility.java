package com.bookmap.plugin.rong.miniviteapp.core.marketdata;

import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.List;

public final class Eligibility {
    // These symbols may trade without meeting the premarket volume thresholds.
    public static final List<String> PREMARKET_VOLUME_WHITELIST = List.of("AMD");
    public static boolean isPremarketVolumeWhitelisted(String symbol) {
        return PREMARKET_VOLUME_WHITELIST.contains(symbol);
    }
    private Eligibility() {}
    public static JsonObject premarketEligibility(double shares, double averageShares, double hardFloor, double absoluteThresholdInMillions, double relativeThreshold) {
        JsonObject json = new JsonObject(); json.addProperty("hardFloorPassed", shares >= hardFloor);
        json.addProperty("absolutePassed", shares / 1000000 >= absoluteThresholdInMillions);
        json.addProperty("relativePassed", Double.isFinite(averageShares) && averageShares > 0 && Double.isFinite(shares) && shares >= averageShares * relativeThreshold); return json;
    }
    public static double impliedMarketCapInBillions(double shares, double price) {
        return price > 0 && shares > 0 ? Math.floor(shares * price / 10000000 + 0.5) / 100 : 0;
    }
    public static String validatePreviousConsolidationArea(JsonObject area, List<Candle> candles) {
        if (candles.isEmpty()) return "";
        double low = area == null ? 0 : Math.min(number(area, "low"), number(area, "high")), high = area == null ? 0 : Math.max(number(area, "low"), number(area, "high"));
        if (area == null || !Double.isFinite(low) || !Double.isFinite(high) || low <= 0 || low >= high) return "missing previous consolidation area for range bound reversal";
        for (Candle candle : candles.subList(Math.max(0, candles.size() - 3), candles.size())) {
            if (candle.open < low || candle.open > high) continue;
            if (candle.close > high || candle.close < low) {
                String direction = candle.close > high ? "breakout" : "breakdown";
                return "previous consolidation " + direction + " on " + MarketClock.marketTime(candle.datetime).date + ": open " + format(candle.open)
                        + " inside [" + format(low) + ", " + format(high) + "], close " + format(candle.close);
            }
        }
        return "";
    }
    private static double number(JsonObject json, String name) { return json.has(name) && json.get(name).isJsonPrimitive() && json.getAsJsonPrimitive(name).isNumber() ? json.get(name).getAsDouble() : Double.NaN; }
    private static String format(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
}
