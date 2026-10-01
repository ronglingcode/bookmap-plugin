package com.bookmap.plugin.rong.miniviteapp.core.configuration;

import com.google.gson.*;
import java.util.ArrayList;
import java.util.List;

/** Mirrors TS definitions and selected-plan validation; all input is local Firestore data. */
public final class TradingConfig {
    private TradingConfig() { }
    private static JsonObject object(JsonObject value, String key) { return value.has(key) && value.get(key).isJsonObject() ? value.getAsJsonObject(key) : new JsonObject(); }
    private static String string(JsonObject value, String key) { return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : ""; }
    private static double number(JsonObject value, String key) { try { return value.has(key) ? value.get(key).getAsDouble() : 0; } catch (RuntimeException error) { return Double.NaN; } }
    private static boolean falseValue(JsonElement value) { return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && !value.getAsBoolean(); }
    private static boolean truthy(JsonElement value) {
        if (value == null || value.isJsonNull()) return false;
        if (!value.isJsonPrimitive()) return true;
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isBoolean() ? primitive.getAsBoolean() : primitive.isNumber() ? primitive.getAsDouble() != 0 : !primitive.getAsString().isEmpty();
    }
    private static void add(JsonArray definitions, String id, boolean isLong, JsonObject base, String areaName) {
        if (base.size() == 0 || !base.has(areaName)) return;
        JsonObject definition = new JsonObject(); definition.addProperty("tradebookID", id); definition.addProperty("isLong", isLong); definition.addProperty("enabled", true);
        definition.add("basePlan", base.deepCopy()); definition.add("entryArea", base.get(areaName).deepCopy()); definitions.add(definition);
    }
    public static JsonArray createTradebookDefinitions(JsonObject plan) {
        JsonArray result = new JsonArray(); JsonObject longPlan = object(plan, "long"), shortPlan = object(plan, "short");
        if (!falseValue(longPlan.get("enabled"))) { add(result, "GapGiveAndGoBookmapReversal", true, object(longPlan, "gapAndGoPlan"), "support"); add(result, "GapDownAndGoUpBookmapReversal", true, object(longPlan, "gapDownAndGoUpPlan"), "support"); }
        if (!falseValue(shortPlan.get("enabled"))) { add(result, "GapAndCrapOfferStepDownReappear", false, object(shortPlan, "gapAndCrapPlan"), "resistance"); add(result, "GapDownAndGoDownOfferStepDownReappear", false, object(shortPlan, "gapDownAndGoDownPlan"), "resistance"); }
        if (plan.has("rangeBoundReversalPlan")) {
            JsonObject range = object(plan, "rangeBoundReversalPlan"), support = object(range, "support").deepCopy(), resistance = object(range, "resistance").deepCopy();
            double sl = Math.min(number(support, "low"), number(support, "high")), sh = Math.max(number(support, "low"), number(support, "high")), rl = Math.min(number(resistance, "low"), number(resistance, "high")), rh = Math.max(number(resistance, "low"), number(resistance, "high"));
            if (sl > 0 && sl < sh && rl < rh && sh < rl) {
                support.addProperty("low", sl); support.addProperty("high", sh); resistance.addProperty("low", rl); resistance.addProperty("high", rh);
                add(result, "RangeBoundBidReversal", true, range, "support"); result.get(result.size() - 1).getAsJsonObject().add("entryArea", support);
                add(result, "RangeBoundOfferReversal", false, range, "resistance"); result.get(result.size() - 1).getAsJsonObject().add("entryArea", resistance);
            }
        }
        return result;
    }
    private static String areaError(String symbol, JsonObject area, String name, boolean requireRange) {
        for (String field : new String[]{"low", "high"}) if (!area.has(field) || !area.get(field).isJsonPrimitive() || !area.get(field).getAsJsonPrimitive().isNumber() || !Double.isFinite(number(area, field))) return symbol + " missing " + name;
        if (Math.min(number(area, "low"), number(area, "high")) <= 0 || requireRange && number(area, "low") == number(area, "high")) return symbol + " missing " + name;
        if (area.has("requireEntryWithinRange") && (!area.get("requireEntryWithinRange").isJsonPrimitive() || !area.get("requireEntryWithinRange").getAsJsonPrimitive().isBoolean())) return symbol + " " + name + " requireEntryWithinRange must be a boolean";
        return "";
    }
    private static final String[][] REASONS = {
        {"gapAndGoPlan", "higherTimeframeSupportReversal", "recentPullback", "nearAboveConsolidationRange", "nearBelowConsolidationRangeTop", "nearPreviousKeyEventLevel", "previousInsideDay", "allTimeHigh"},
        {"gapAndCrapPlan", "heavySupplyZoneDays", "recentRallyWithoutPullback", "extendedGapUpInAtr", "earnings", "topEdgeOfCurrentRange", "nearBelowPreviousEventKeyLevel"},
        {"gapDownAndGoDownPlan", "higherTimeframeResistanceReversal", "nearBelowConsolidationRange", "nearBelowConsolidationRangeTop", "buyersTrappedBelowThisLevel", "previousInsideDay"},
        {"gapDownAndGoUpPlan", "nearAboveSupport", "nearAboveKeyEventLevel"}
    };
    public static String validateTradingPlan(JsonObject plan) {
        String symbol = string(plan, "symbol");
        if (!plan.has("corePlan") || !plan.get("corePlan").isJsonPrimitive() || !plan.get("corePlan").getAsJsonPrimitive().isString() || string(plan, "corePlan").trim().length() <= 50) return symbol + " core plan must contain more than 50 characters";
        if (!truthy(object(object(plan, "analysis"), "gap").get("pdc"))) return symbol + " missing gap pdc";
        JsonObject atr = object(plan, "atr"); if (!(number(atr, "average") > 0) || !(number(atr, "mutiplier") > 0) || !(number(atr, "minimumMultipler") > 0)) return symbol + " missing atr";
        if (plan.has("rangeBoundReversalPlan")) {
            JsonObject range = object(plan, "rangeBoundReversalPlan"), support = object(range, "support"), resistance = object(range, "resistance");
            String error = areaError(symbol, support, "support zone for range bound reversal", true); if (!error.isEmpty()) return error;
            error = areaError(symbol, resistance, "resistance zone for range bound reversal", true); if (!error.isEmpty()) return error;
            if (Math.max(number(support, "low"), number(support, "high")) >= Math.min(number(resistance, "low"), number(resistance, "high"))) return symbol + " range bound reversal support zone must be below resistance zone";
        }
        for (String side : new String[]{"long", "short"}) {
            if (!plan.has(side) || !plan.get(side).isJsonObject()) return symbol + " missing " + side + " plan";
            JsonObject direction = plan.getAsJsonObject(side); if (falseValue(direction.get("enabled"))) continue;
            String target = string(direction, "firstTargetToAdd"); double price; try { price = Double.parseDouble(target); } catch (RuntimeException error) { price = 0; }
            if (!List.of("vwap", "premarketHigh", "premarketLow").contains(target) && !(price > 0)) return symbol + " missing first target to add";
            if (!direction.has("finalTargets") || !direction.get("finalTargets").isJsonArray() || direction.getAsJsonArray("finalTargets").size() < 2) return symbol + " need at least 2 final targets";
            JsonArray targets = direction.getAsJsonArray("finalTargets");
            for (int index = 0; index < targets.size(); index++) {
                JsonObject value = targets.get(index).getAsJsonObject(); if (!truthy(value.get("partialCount"))) return symbol + " missing partial count for final target[" + index + "]";
                if (!truthy(value.get("text"))) return symbol + " missing text for final target[" + index + "]";
                if (!truthy(value.get("rrr")) && !truthy(value.get("level")) && !truthy(value.get("atr"))) return symbol + " missing atr,rrr,level for final target[" + index + "]";
            }
            boolean hasTradebook = plan.has("rangeBoundReversalPlan");
            for (String[] reasons : REASONS) if (direction.has(reasons[0])) {
                String name = reasons[0], area = name.equals("gapAndGoPlan") || name.equals("gapDownAndGoUpPlan") ? "support" : "resistance";
                JsonObject base = object(direction, name); String error = areaError(symbol, object(base, area), name + " " + area, false); if (!error.isEmpty()) return error;
                boolean reason = false; for (int index = 1; index < reasons.length; index++) if (truthy(base.get(reasons[index]))) reason = true;
                if (!reason) return symbol + " missing one reason set for " + name; hasTradebook = true;
            }
            if (!hasTradebook) return symbol + " missing best tradebook";
        }
        return "";
    }
    public static final class Result {
        public final JsonArray plans;
        public final List<String> symbols;
        public final String profile;
        public final JsonObject tradingSettings;
        private Result(JsonObject raw, List<String> symbols) {
            plans = raw.getAsJsonArray("plans").deepCopy(); this.symbols = List.copyOf(symbols); profile = string(raw, "activeProfileName").isEmpty() ? "momentumSimple" : string(raw, "activeProfileName");
            tradingSettings = raw.has("tradingSettings") && raw.get("tradingSettings").isJsonObject() ? raw.getAsJsonObject("tradingSettings").deepCopy() : new JsonObject();
            if (!raw.has("tradingSettings") || raw.get("tradingSettings").isJsonNull()) { tradingSettings.addProperty("useSingleOrderForEntry", false); tradingSettings.addProperty("snapMode", true); }
        }
        public JsonObject plan(String symbol) { for (JsonElement value : plans) if (string(value.getAsJsonObject(), "symbol").equals(symbol)) return value.getAsJsonObject(); return null; }
        public JsonObject toJson() { JsonObject result = new JsonObject(); result.add("plans", plans.deepCopy()); JsonArray stocks = new JsonArray(); symbols.forEach(stocks::add); result.add("symbols", stocks); result.addProperty("profile", profile); result.add("tradingSettings", tradingSettings.deepCopy()); return result; }
    }
    public static Result readTradingConfig(JsonObject raw, int maxStocks) {
        if (raw == null || !raw.has("plans") || !raw.get("plans").isJsonArray() || !raw.has("stockSelections") || !raw.get("stockSelections").isJsonArray()) throw new IllegalArgumentException("Trading configuration missing plans/stockSelections");
        List<String> symbols = new ArrayList<>();
        for (JsonElement value : raw.getAsJsonArray("stockSelections")) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isEmpty()) throw new IllegalArgumentException("Trading configuration contains an invalid stock selection"); symbols.add(value.getAsString());
        }
        if (symbols.size() > maxStocks) throw new IllegalArgumentException("more than " + maxStocks + " stocks in watchlist: " + String.join(", ", symbols));
        Result result = new Result(raw, symbols);
        for (String symbol : symbols) { JsonObject plan = result.plan(symbol); if (plan == null) throw new IllegalArgumentException(symbol + " missing trading plans"); String error = validateTradingPlan(plan); if (!error.isEmpty()) throw new IllegalArgumentException(error); }
        return result;
    }
}
