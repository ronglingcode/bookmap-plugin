package com.bookmap.plugin.rong.miniviteapp.core.algorithms;

import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;

/** Captures directional session levels and depth walls when an entry is submitted. */
public final class TakeProfit {
    private TakeProfit() { }
    public static JsonArray getEntryProfitTargets(double totalShares, double entryPrice, double riskPrice,
            boolean isLong, JsonObject walls, int count, JsonObject market) {
        Models.require(count > 0, "entry requires at least one partial");
        double target3R = round(entryPrice + (isLong ? 1 : -1) * 3 * Math.abs(entryPrice - riskPrice));
        double threshold = walls == null ? Double.NaN : Models.number(walls, "effectiveWallThreshold");
        Set<Double> seen = new TreeSet<Double>(isLong ? Comparator.<Double>naturalOrder() : Comparator.<Double>reverseOrder());
        if (walls != null && Models.positive(threshold)) {
            String side = isLong ? "largeAsks" : "largeBids";
            if (walls.has(side)) for (var element : walls.getAsJsonArray(side)) {
                var level = element.getAsJsonArray();
                double price = level.get(0).getAsDouble(), size = level.get(1).getAsDouble();
                if (Double.isFinite(price) && Double.isFinite(size) && size >= threshold &&
                        profitable(round(price), entryPrice, isLong)) seen.add(round(price));
            }
        }
        List<Double> prices = new ArrayList<>(seen);
        if (prices.size() > 3) prices = new ArrayList<>(prices.subList(0, 3));
        seen.clear();
        seen.addAll(prices);
        if (market != null) {
            addLevel(seen, Models.number(market, "vwap"), entryPrice, isLong);
            addLevel(seen, Models.number(market, isLong ? "premarketHigh" : "premarketLow"), entryPrice, isLong);
        }
        addLevel(seen, entryPrice + (isLong ? 1 : -1) * 2 * Math.abs(entryPrice - riskPrice), entryPrice, isLong);
        prices = new ArrayList<>(seen);
        // Smaller risk-method entries retain their existing number of brackets and a 3R remainder.
        if (prices.size() >= count) prices = new ArrayList<>(prices.subList(0, Math.max(0, count - 1)));
        int levelCount = prices.size();
        while (prices.size() < count) prices.add(target3R);
        long shares = (long) Math.floor(totalShares), levelShares = shares / 10;
        long remaining = shares - levelShares * levelCount;
        long base = remaining / (count - levelCount), remainder = remaining % (count - levelCount);
        JsonArray result = new JsonArray();
        for (int i = 0; i < count; i++) {
            long quantity = i < levelCount ? levelShares : base + (i - levelCount < remainder ? 1 : 0);
            if (quantity == 0) continue;
            JsonObject target = new JsonObject(); target.addProperty("target", prices.get(i));
            target.addProperty("quantity", quantity); result.add(target);
        }
        return result;
    }
    private static boolean profitable(double price, double entry, boolean isLong) {
        return Models.positive(price) && (isLong ? price > entry : price < entry);
    }
    private static void addLevel(Set<Double> levels, double price, double entry, boolean isLong) {
        double rounded = round(price);
        if (Double.isFinite(price) && profitable(rounded, entry, isLong)) levels.add(rounded);
    }
    public static double round(double price) { return Math.round(price * 100) / 100.0; }
}
