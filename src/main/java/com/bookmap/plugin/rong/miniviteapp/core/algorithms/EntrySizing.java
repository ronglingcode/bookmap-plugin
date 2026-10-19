package com.bookmap.plugin.rong.miniviteapp.core.algorithms;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.LinkedHashSet;
import java.util.Set;

public final class EntrySizing {
    public static final int FIXED_SHARE_PARTIALS = 10;
    private EntrySizing() { }

    public static long fixedShares(String method) {
        if (!method.trim().matches("\\d+ sh")) return 0;
        try {
            long value = Long.parseLong(method.trim().split(" ")[0]);
            return value >= FIXED_SHARE_PARTIALS && value <= 9007199254740991L ? value : 0;
        } catch (NumberFormatException invalid) { return 0; }
    }

    public static JsonArray entryMethods(JsonArray options) {
        JsonArray methods = new JsonArray();
        for (String risk : new String[]{"1 R", "0.5 R", "0.3 R", "0.2 R", "0.1 R"}) methods.add(risk);
        Set<Long> seen = new LinkedHashSet<>();
        for (JsonElement item : options) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) continue;
            double value = item.getAsDouble();
            if (Double.isFinite(value) && value >= FIXED_SHARE_PARTIALS && value <= 9007199254740991L
                    && value == Math.floor(value) && seen.add((long) value)) methods.add((long) value + " sh");
        }
        return methods;
    }
}
