package com.bookmap.plugin.rong.miniviteapp.libraries.massive;

import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class Mapper {
    private Mapper() {}
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
