package com.bookmap.plugin.rong.miniviteapp.core.algorithms;

import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;

/** Mirror of algorithms/takeProfit.ts and its pure entryTargets helper. */
public final class TakeProfit {
    private TakeProfit() { }
    public static JsonArray getEntryProfitTargets(double totalShares, double entryPrice, double riskPrice,
            boolean isLong, JsonObject walls, int count) {
        double target3R = round(entryPrice + (isLong ? 1 : -1) * 3 * Math.abs(entryPrice - riskPrice));
        double threshold = walls == null ? Double.NaN : Models.number(walls, "effectiveWallThreshold");
        Set<Double> seen = new TreeSet<Double>(isLong ? Comparator.<Double>naturalOrder() : Comparator.<Double>reverseOrder());
        if (walls != null && Models.positive(threshold)) {
            String side = isLong ? "largeAsks" : "largeBids";
            if (walls.has(side)) for (var element : walls.getAsJsonArray(side)) {
                var level = element.getAsJsonArray();
                double price = level.get(0).getAsDouble(), size = level.get(1).getAsDouble();
                if (Double.isFinite(price) && Double.isFinite(size) && size >= threshold &&
                        (isLong ? price > entryPrice : price < entryPrice)) seen.add(round(price));
            }
        }
        List<Double> prices = new ArrayList<>(seen);
        if (prices.size() > 3) prices = new ArrayList<>(prices.subList(0, 3));
        while (prices.size() < count) prices.add(target3R);
        long shares = (long) Math.floor(totalShares), base = shares / count, remainder = shares % count;
        JsonArray result = new JsonArray();
        for (int i = 0; i < count; i++) {
            long quantity = base + (i < remainder ? 1 : 0);
            if (quantity == 0) continue;
            JsonObject target = new JsonObject(); target.addProperty("target", prices.get(i));
            target.addProperty("quantity", quantity); result.add(target);
        }
        return result;
    }
    public static double round(double price) { return Math.round(price * 100) / 100.0; }
}
