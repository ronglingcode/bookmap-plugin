package com.bookmap.plugin.rong.miniviteapp.libraries.massive;

import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import com.bookmap.plugin.rong.miniviteapp.models.Trade;
import com.bookmap.plugin.rong.miniviteapp.core.marketdata.MarketClock;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class Mapper {
    private Mapper() {}
    private static final java.util.Set<Integer> NON_UPDATING_CONDITIONS = java.util.Set.of(2, 7, 12, 13, 15, 16, 20, 21, 37, 52, 53);
    public static boolean shouldFilterTrade(Trade trade) {
        return MarketClock.marketTime(trade.timestamp).isRegularSession && trade.conditions.stream().anyMatch(NON_UPDATING_CONDITIONS::contains);
    }
    public static Trade mapWebSocketTrade(JsonObject value) {
        double size = decimalSize(value, "ds", "s");
        if (!value.has("ev") || !value.get("ev").isJsonPrimitive() || !value.get("ev").getAsString().equals("T") || !value.has("sym") || !value.get("sym").isJsonPrimitive() || !value.get("sym").getAsJsonPrimitive().isString() || !valid(value, "t") || !valid(value, "p") || number(value, "p") <= 0 || size <= 0) return null;
        return new Trade(value.get("sym").getAsString(), (long) number(value, "t"), number(value, "p"), size, optional(value, "q"), optional(value, "i"), valid(value, "x") ? value.get("x").getAsInt() : null, conditions(value, "c"));
    }
    public static Trade mapRestTrade(String symbol, JsonObject value) {
        long timestamp = new java.math.BigInteger(value.get("sip_timestamp").getAsString()).divide(java.math.BigInteger.valueOf(1000000)).longValueExact();
        double size = decimalSize(value, "decimal_size", "size");
        if (!valid(value, "price") || number(value, "price") <= 0 || size <= 0) throw new IllegalArgumentException("Massive trade missing price/size");
        return new Trade(symbol, timestamp, number(value, "price"), size, optional(value, "sequence_number"), optional(value, "id"), valid(value, "exchange") ? value.get("exchange").getAsInt() : null, conditions(value, "conditions"));
    }
    private static boolean valid(JsonObject value, String name) { return value.has(name) && isNumber(value.get(name)); }
    private static String optional(JsonObject value, String name) { return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsString() : null; }
    private static java.util.List<Integer> conditions(JsonObject value, String name) {
        java.util.List<Integer> result = new java.util.ArrayList<>();
        if (value.has(name) && value.get(name).isJsonArray()) for (JsonElement element : value.getAsJsonArray(name)) if (isNumber(element)) result.add(element.getAsInt());
        return result;
    }
    private static double decimalSize(JsonObject value, String decimalName, String integerName) {
        if (value.has(decimalName) && !value.get(decimalName).isJsonNull()) {
            try {
                double parsed = new java.math.BigDecimal(value.get(decimalName).getAsString()).doubleValue();
                if (Double.isFinite(parsed) && parsed > 0) return parsed;
            } catch (RuntimeException ignored) { }
        }
        return valid(value, integerName) ? number(value, integerName) : 0;
    }
    public static Candle mapAggregate(String symbol, JsonObject value) {
        double vwap = value.has("vw") && isNumber(value.get("vw")) ? value.get("vw").getAsDouble() : 0;
        return new Candle(symbol, (long) number(value, "t"), number(value, "o"), number(value, "h"),
                number(value, "l"), number(value, "c"), number(value, "v"), vwap);
    }
    private static boolean isNumber(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && Double.isFinite(value.getAsDouble());
    }
    private static double number(JsonObject value, String name) {
        if (!value.has(name) || !isNumber(value.get(name))) throw new IllegalArgumentException("Massive aggregate missing " + name);
        return value.get(name).getAsDouble();
    }
}
