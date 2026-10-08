package com.bookmap.plugin.rong.miniviteapp.core.controllers;

import com.google.gson.*;
import static com.bookmap.plugin.rong.miniviteapp.models.DomainJson.*;

/** Mirrors TS workflows; no I/O, timers, globals or Bookmap API. */
public final class Workflows {
    private Workflows() { }
    public static JsonObject fallbackProfitReset(double netQuantity, double currentPrice, double low, double high, double batchCount) {
        boolean isLong = netQuantity > 0; double remaining = Math.abs(netQuantity);
        if (!Double.isFinite(remaining) || remaining <= 0) throw new IllegalArgumentException("No position for profit reset");
        double stopLoss = Math.round((isLong ? low : high) * 100) / 100.0;
        if (!Double.isFinite(currentPrice) || currentPrice <= 0 || !Double.isFinite(isLong ? low : high) || stopLoss <= 0
                || (isLong ? stopLoss >= currentPrice : stopLoss <= currentPrice))
            throw new IllegalArgumentException("Profit reset fallback requires current price and a day stop on the protective side");
        double rawTarget = currentPrice + 2 * (currentPrice - stopLoss);
        if (!Double.isFinite(rawTarget)) throw new IllegalArgumentException("Invalid profit reset fallback target");
        double target = Math.round(rawTarget * 100) / 100.0;
        if (!Double.isFinite(target) || target <= 0 || (isLong ? target <= currentPrice : target >= currentPrice))
            throw new IllegalArgumentException("Invalid profit reset fallback target");
        int count = (int) Math.min(Math.max(1, Math.floor(remaining)), Double.isFinite(batchCount) && batchCount > 0 ? Math.max(1, Math.floor(batchCount)) : 10);
        double quantity = Math.floor(remaining / count); JsonArray targets = new JsonArray();
        for (int index = 0; index < count; index++) { JsonObject value = new JsonObject(); value.addProperty("target", target);
            value.addProperty("quantity", quantity + Math.min(1, Math.max(0, remaining - quantity * count - index))); targets.add(value); }
        JsonObject result = new JsonObject(); result.addProperty("stopLoss", stopLoss); result.add("targets", targets); return result;
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
