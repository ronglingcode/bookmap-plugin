package com.bookmap.plugin.rong.miniviteapp.core.marketdata;

import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import com.bookmap.plugin.rong.miniviteapp.runtime.MarketClock;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class PremarketVolume {
    private PremarketVolume() {}
    public static double typicalPrice(Candle candle) {
        return candle.vwap > 0 && candle.vwap >= candle.low && candle.vwap <= candle.high
                ? candle.vwap : (candle.high + candle.low + candle.close) / 3;
    }
    public static JsonObject calculatePremarketVolume(List<Candle> candles) {
        Map<String, double[]> days = new TreeMap<>();
        for (Candle candle : candles) {
            MarketClock.Time time = MarketClock.marketTime(candle.datetime);
            if (!time.isPremarket) continue;
            double[] day = days.computeIfAbsent(time.date, key -> new double[2]);
            day[0] += Math.floor(typicalPrice(candle) * candle.volume + 0.5); day[1] += candle.volume;
        }
        List<Map.Entry<String, double[]>> entries = new ArrayList<>(days.entrySet());
        double[] latest = entries.isEmpty() ? new double[2] : entries.remove(entries.size() - 1).getValue();
        JsonArray dollars = new JsonArray(), shares = new JsonArray();
        List<Double> sorted = new ArrayList<>();
        double sumDollar = 0, sumShares = 0;
        for (Map.Entry<String, double[]> entry : entries) {
            dollars.add(perDay(entry.getKey(), entry.getValue()[0])); shares.add(perDay(entry.getKey(), entry.getValue()[1]));
            sumDollar += entry.getValue()[0]; sumShares += entry.getValue()[1]; sorted.add(entry.getValue()[0]);
        }
        Collections.sort(sorted);
        int count = sorted.size(), middle = count / 2;
        double median = count == 0 ? 0 : count % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
        JsonObject result = new JsonObject();
        result.add("previousDaysDollar", dollars); result.add("previousDaysShares", shares);
        result.addProperty("previousDaysDollarAverage", count == 0 ? 0 : sumDollar / count);
        result.addProperty("previousDaysSharesAverage", count == 0 ? 0 : sumShares / count);
        result.addProperty("previousDaysDollarMedian", median);
        result.addProperty("lastDayDollar", latest[0]); result.addProperty("lastDayShares", latest[1]);
        result.addProperty("rvol", median > 0 ? latest[0] / median : 0); return result;
    }
    private static JsonObject perDay(String day, double value) {
        JsonObject result = new JsonObject(); result.addProperty("day", day); result.addProperty("data", value); return result;
    }
}
