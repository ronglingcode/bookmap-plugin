package com.bookmap.plugin.rong.miniviteapp.core.controllers;

import com.bookmap.plugin.rong.miniviteapp.core.marketdata.MarketClock;
import com.google.gson.*;
import java.util.*;
import static com.bookmap.plugin.rong.miniviteapp.models.DomainJson.*;

/** Mirrors TS workflows; no I/O, timers, globals or Bookmap API. */
public final class Workflows {
    private Workflows() { }
    public static double trailStopPrice(JsonArray candles, boolean isLong, int timeframe, boolean shift) {
        if (timeframe != 5 && timeframe != 15 && timeframe != 30) throw new IllegalArgumentException("Unsupported trailing timeframe");
        List<JsonObject> regular = new ArrayList<>(); for (JsonElement item : candles) if (MarketClock.marketTime((long) number(item.getAsJsonObject(), "datetime")).isRegularSession) regular.add(item.getAsJsonObject());
        regular.sort(Comparator.comparingDouble(candle -> number(candle, "datetime"))); Map<Integer, double[]> groups = new LinkedHashMap<>(); boolean pattern = false;
        for (int index = 0; index < regular.size(); index++) { JsonObject candle = regular.get(index); int key = (int) Math.floor(MarketClock.marketTime((long) number(candle, "datetime")).minutesSinceMarketOpen / timeframe);
            double[] value = groups.computeIfAbsent(key, id -> new double[]{number(candle, "high"), number(candle, "low")}); value[0] = Math.max(value[0], number(candle, "high")); value[1] = Math.min(value[1], number(candle, "low"));
            if (index > 0 && (isLong ? number(candle, "low") < number(regular.get(index - 1), "low") : number(candle, "high") > number(regular.get(index - 1), "high"))) pattern = true;
        }
        if (groups.size() < 2) throw new IllegalArgumentException("Not enough trailing bars");
        if (shift && !pattern) throw new IllegalArgumentException(isLong ? "No lower low for market trailing exit" : "No higher high for market trailing exit");
        List<double[]> bars = new ArrayList<>(groups.values()); double[] closed = bars.get(bars.size() - 2); return isLong ? (Math.floor(closed[1] * 100) - 1) / 100 : (Math.ceil(closed[0] * 100) + 1) / 100;
    }
    public static JsonArray profitResetTargets(JsonArray targets, double remaining) {
        if (targets.size() <= 1) throw new IllegalArgumentException("Profit reset requires multiple captured targets");
        if (!(remaining > 0)) throw new IllegalArgumentException("No position for profit reset"); JsonArray result = new JsonArray();
        for (int index = targets.size() - 1; index >= 0 && remaining > 0; index--) { JsonObject target = targets.get(index).getAsJsonObject();
            if (!(number(target, "target") > 0) || !(number(target, "quantity") > 0)) throw new IllegalArgumentException("Invalid captured profit target");
            double quantity = Math.min(number(target, "quantity"), remaining); JsonObject value = target.deepCopy(); value.addProperty("quantity", quantity); result.add(value); remaining -= quantity;
        }
        if (remaining > 0) throw new IllegalArgumentException("Captured targets do not cover the current position"); return result;
    }
    public static JsonObject stopDiscipline(String phase, double current, double initial, boolean isLong, JsonArray pairs, double low, double high) {
        JsonObject result = new JsonObject(); String next = phase.isEmpty() ? "idle" : phase; double needed = 0; boolean remind = false;
        if (current > 0 && initial > 0) { double required = Math.max(0, current - initial * 0.5), tightened = 0;
            for (JsonElement pair : pairs) { JsonObject stop = object(pair.getAsJsonObject(), "STOP"); if (stop.size() > 0 && (isLong ? number(stop, "price") > low : number(stop, "price") < high)) tightened += number(stop, "quantity"); }
            if (next.equals("done") && tightened < required || next.equals("idle") && current < initial * 0.9) next = "needs_tighten";
            else if (next.equals("needs_tighten") && tightened >= required) next = "done";
            needed = Math.max(0, current - Math.floor(initial * 0.5)); remind = next.equals("needs_tighten") && needed > 1;
        }
        result.addProperty("phase", next); result.addProperty("remind", remind); result.addProperty("neededShares", needed); return result;
    }
    public static JsonObject pendingStopRefresh(JsonArray entries, int pairCount, double seconds, String oldId, double low, double high) {
        if (seconds < 0 || seconds >= 300 || pairCount > 0 || entries.size() != 1) return null;
        JsonObject order = entries.get(0).getAsJsonObject(); boolean isLong = bool(order, "isBuy"); double stop = isLong ? low : high;
        if (string(order, "orderID").equals(oldId) || !(number(order, "price") > 0) || !(number(order, "exitStopPrice") > 0) || !(stop > 0)) return null;
        if (isLong ? stop >= number(order, "exitStopPrice") : stop <= number(order, "exitStopPrice")) return null;
        JsonObject result = new JsonObject(); result.addProperty("orderID", string(order, "orderID")); result.addProperty("stopPrice", stop); result.addProperty("isLong", isLong); result.addProperty("entryPrice", number(order, "price")); return result;
    }
    public static JsonObject firstVwapTouch(JsonObject previous, JsonObject position, double price, double vwap) {
        boolean isNew = position != null && !string(previous, "positionKey").equals(string(position, "positionKey")); JsonObject state = (isNew ? position : previous).deepCopy(); boolean notify = false;
        if (position != null) { boolean isLong = bool(position, "isLong"); boolean away = isLong ? number(position, "entryPrice") < number(position, "entryVwap") : number(position, "entryPrice") > number(position, "entryVwap"); boolean touched = vwap > 0 && (isLong ? price >= vwap : price <= vwap);
            notify = away && touched && !string(state, "alertedPositionKey").equals(string(position, "positionKey")); if (notify) state.addProperty("alertedPositionKey", string(position, "positionKey")); }
        JsonObject result = new JsonObject(); result.add("state", state); result.addProperty("persist", isNew || notify); result.addProperty("notify", notify); return result;
    }
    public static double completedPartials(double initial, double exited, int remainingPairs, double count) {
        double fromPairs = Math.max(0, count - Math.min(count, Math.max(0, remainingPairs))); return initial > 0 ? Math.min(Math.max(0, Math.min(count, Math.floor(exited / (initial / count) + 0.5))), fromPairs) : fromPairs;
    }
    public static JsonObject buyingPowerTargets(JsonArray targets, double entryPrice, double available) {
        double original = 0; for (JsonElement item : targets) original += number(item.getAsJsonObject(), "quantity"); boolean halved = available <= entryPrice * original;
        JsonArray sized = targets.deepCopy(); double total = 0; for (JsonElement item : sized) { JsonObject target = item.getAsJsonObject(); double quantity = number(target, "quantity") / (halved ? 2 : 1); target.addProperty("quantity", quantity); total += quantity; }
        JsonObject result = new JsonObject(); result.add("targets", sized); result.addProperty("totalQuantity", total); result.addProperty("halved", halved); result.addProperty("insufficient", available <= entryPrice * total); return result;
    }
}
