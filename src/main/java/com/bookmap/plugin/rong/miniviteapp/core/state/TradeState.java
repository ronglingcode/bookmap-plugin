package com.bookmap.plugin.rong.miniviteapp.core.state;

import com.google.gson.*;
import java.time.LocalDate;

/** Persisted domain state only. Runtime timers/network/Bookmap never enter the document. */
public final class TradeState {
    private final JsonObject state;
    private final long now;
    public static JsonObject timestamp(long now) { JsonObject value = new JsonObject(); value.addProperty("seconds", now / 1000); value.addProperty("nanoseconds", now % 1000 * 1000000); return value; }
    public static String dateLabel(String date) { LocalDate value = LocalDate.parse(date); return value.getMonthValue() + "/" + value.getDayOfMonth() + "/" + value.getYear(); }
    public static JsonObject defaultBreakout(boolean isLong, long now) {
        JsonObject value = new JsonObject();
        for (String field : new String[]{"entryPrice", "stopLossPrice", "riskLevel", "initialQuantity", "sizeMultipler", "maxPullbackAllowed", "maxPullbackReached"}) value.addProperty(field, 0);
        for (String field : new String[]{"coreInvalidationLevel", "lowestExitBatchCount", "closedOutsideRatio"}) value.addProperty(field, -1);
        for (String field : new String[]{"hasValue", "isMarketOrder", "adjustedTargetDueToMaxPullback", "coreTargetReminderShown"}) value.addProperty(field, false);
        value.addProperty("status", "None"); value.addProperty("exitDescription", ""); value.addProperty("stopTightenPhase", "idle");
        JsonObject result = new JsonObject(); result.addProperty("isSingleOrder", false); result.add("profitTargets", new JsonArray()); result.addProperty("totalQuantity", 0); result.addProperty("tradeBookID", ""); value.add("submitEntryResult", result);
        JsonObject plan = new JsonObject(), configs = new JsonObject(); configs.addProperty("requireReversal", true); plan.add("planConfigs", configs);
        for (String field : new String[]{"coreTarget", "coreCount", "runnerCount"}) plan.addProperty(field, 0);
        plan.addProperty("runnerTriggerCondition", ""); value.add("plan", plan);
        value.add("submitTime", timestamp(now)); value.addProperty("isLong", isLong); return value;
    }
    public static JsonObject defaultSymbolState(long now) {
        JsonObject value = new JsonObject(); value.add("breakoutTradeStateForLong", defaultBreakout(true, now)); value.add("breakoutTradeStateForShort", defaultBreakout(false, now)); value.addProperty("peakRiskMultiple", 0); return value;
    }
    public static JsonObject acceptedBreakout(JsonObject entry, long now) {
        boolean isLong = entry.get("isLong").getAsBoolean(); JsonObject value = defaultBreakout(isLong, now); value.addProperty("hasValue", true);
        value.add("entryPrice", entry.get("entryPrice")); value.add("stopLossPrice", entry.get("stopOutPrice")); value.add("riskLevel", entry.get("stopOutPrice"));
        value.add("initialQuantity", entry.getAsJsonObject("submitEntryResult").get("totalQuantity")); value.addProperty("status", "Pending");
        value.add("isMarketOrder", entry.get("useMarketOrder")); value.add("submitEntryResult", entry.get("submitEntryResult").deepCopy()); value.add("plan", entry.get("basePlan").deepCopy()); value.add("sizeMultipler", entry.get("multiplier"));
        double price = entry.get("entryPrice").getAsDouble(), stop = entry.get("stopOutPrice").getAsDouble(); value.addProperty("maxPullbackAllowed", Math.round((price + (isLong ? -1 : 1) * 0.75 * Math.abs(price - stop)) * 100) / 100.0); return value;
    }
    public TradeState(String date, double balance, long now, JsonObject restored) {
        this.now = now;
        String oldDate = restored != null && restored.has("date") ? restored.get("date").getAsString() : "";
        if (oldDate.equals(date) || oldDate.equals(dateLabel(date))) state = restored.deepCopy();
        else { state = new JsonObject(); state.addProperty("date", dateLabel(date)); state.addProperty("initialBalance", balance); }
        if (!state.has("stateBySymbol")) state.add("stateBySymbol", new JsonObject()); if (!state.has("readOnlyStateBySymbol")) state.add("readOnlyStateBySymbol", new JsonObject());
    }
    public synchronized JsonObject symbol(String symbol) {
        JsonObject map = state.getAsJsonObject("stateBySymbol"); if (!map.has(symbol)) map.add(symbol, defaultSymbolState(now)); return map.getAsJsonObject(symbol);
    }
    public synchronized JsonObject direction(String symbol, boolean isLong) {
        JsonObject value = symbol(symbol); String key = isLong ? "breakoutTradeStateForLong" : "breakoutTradeStateForShort"; if (!value.has(key)) value.add(key, defaultBreakout(isLong, now)); return value.getAsJsonObject(key);
    }
    public synchronized boolean acceptEntry(String symbol, JsonObject entry, JsonObject atr, long now) {
        if (entry.has("preserveExistingTrade") && entry.get("preserveExistingTrade").getAsBoolean()) return false;
        JsonObject value = symbol(symbol); value.add(entry.get("isLong").getAsBoolean() ? "breakoutTradeStateForLong" : "breakoutTradeStateForShort", acceptedBreakout(entry, now)); value.add("activeBasePlan", entry.get("basePlan").deepCopy());
        JsonObject readOnly = new JsonObject(); readOnly.add("atr", atr.deepCopy()); state.getAsJsonObject("readOnlyStateBySymbol").add(symbol, readOnly); return true;
    }
    public synchronized void updateCorePlan(String symbol, boolean isLong, double target, double count) {
        if (!Double.isFinite(target) || target <= 0 || count != Math.floor(count) || count < 0 || count > 7) throw new IllegalArgumentException("Invalid core target/count");
        JsonObject value = direction(symbol, isLong); double price = value.get("entryPrice").getAsDouble();
        if (!value.get("hasValue").getAsBoolean() || (isLong ? target <= price : target >= price)) throw new IllegalArgumentException("Core target must be on the profitable side of the active entry");
        value.getAsJsonObject("plan").addProperty("coreTarget", target); value.getAsJsonObject("plan").addProperty("coreCount", count);
        if (symbol(symbol).has("activeBasePlan")) { symbol(symbol).getAsJsonObject("activeBasePlan").addProperty("coreTarget", target); symbol(symbol).getAsJsonObject("activeBasePlan").addProperty("coreCount", count); }
    }
    public synchronized JsonObject snapshot() { return state.deepCopy(); }
}
