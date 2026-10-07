package com.bookmap.plugin.rong.miniviteapp.core.controllers;

import com.bookmap.plugin.rong.miniviteapp.core.configuration.TradingConfig;
import com.bookmap.plugin.rong.miniviteapp.core.marketdata.Levels;
import com.google.gson.*;
import java.util.*;
import static com.bookmap.plugin.rong.miniviteapp.models.DomainJson.*;

/** Mirrors TS nativeViews: local domain -> existing display protocol, without Bookmap APIs. */
public final class NativeViews {
    private NativeViews() { }
    private static final Map<String, String[]> NAMES = Map.of(
        "GapGiveAndGoBookmapReversal", new String[]{"Gap, Give & Go", "Gap, give and go bookmap reversal"},
        "GapDownAndGoUpBookmapReversal", new String[]{"Gap Down & Go Up Bookmap Reversal", "gap down & go up bookmap bid reversal"},
        "GapAndCrapOfferStepDownReappear", new String[]{"Gap & Crap Offer Step Down Or Reappear", "Gap & Crap offer step down / reappear"},
        "GapDownAndGoDownOfferStepDownReappear", new String[]{"Gap Down & Go Down Offer Step Down Or Reappear", "Gap Down & Go Down offer step down / reappear"},
        "RangeBoundBidReversal", new String[]{"Range Bound Bid Reversal", "Range Bound bid reversal"},
        "RangeBoundOfferReversal", new String[]{"Range Bound Offer Reversal", "Range Bound offer reversal"});
    public static JsonArray sortedExitPairs(JsonArray source) {
        List<JsonObject> pairs = new ArrayList<>(); source.forEach(item -> pairs.add(item.getAsJsonObject().deepCopy()));
        pairs.sort((a, b) -> !a.has("LIMIT") ? b.has("LIMIT") ? 1 : 0 : !b.has("LIMIT") ? -1 : bool(object(a, "LIMIT"), "isBuy") ? Double.compare(number(object(b, "LIMIT"), "price"), number(object(a, "LIMIT"), "price")) : Double.compare(number(object(a, "LIMIT"), "price"), number(object(b, "LIMIT"), "price")));
        JsonArray result = new JsonArray(); pairs.forEach(result::add); return result;
    }
    public static double positionRisk(double net, double average, JsonArray pairs, double low, double high) {
        double covered = 0, dollars = 0; for (JsonElement item : pairs) { JsonObject stop = object(item.getAsJsonObject(), "STOP"); if (stop.size() == 0) continue;
            double quantity = Math.min(Math.max(0, Math.abs(net) - covered), number(stop, "quantity")); covered += quantity; dollars += Math.abs(average - number(stop, "price")) * quantity; }
        double extreme = net > 0 ? low : high; if (extreme > 0) dollars += Math.abs(average - extreme) * Math.max(0, Math.abs(net) - covered); return dollars;
    }
    private static JsonObject msg(JsonObject view, String type) { JsonObject result = message(type); result.remove("version"); result.addProperty("symbol", string(view, "symbol")); result.addProperty("timestamp", number(view, "timestamp")); return result; }
    private static String mode(JsonObject analysis, String field) { String value = string(analysis, field); return List.of("yes", "warning", "no").contains(value) ? value : "no"; }
    private static void zone(JsonArray zones, Set<String> seen, JsonObject area, String label, String color) {
        double low = Math.min(number(area, "low"), number(area, "high")), high = Math.max(number(area, "low"), number(area, "high")); String key = low + ":" + high;
        if (!(low > 0 && high > low) || !seen.add(key)) return; JsonObject zone = new JsonObject(); zone.addProperty("low", low); zone.addProperty("high", high);
        zone.addProperty("label", string(area, "label").trim().isEmpty() ? label : string(area, "label").trim()); zone.addProperty("color", string(area, "color").trim().isEmpty() ? color : string(area, "color").trim()); zones.add(zone);
    }
    private static void order(JsonArray orders, JsonObject value, String role, JsonObject pair, int index) {
        if (value.size() == 0) return; JsonObject order = value.deepCopy(); order.addProperty("role", role); if (pair != null) { for (String field : new String[]{"source", "parentOrderID"}) if (pair.has(field)) order.add(field, pair.get(field).deepCopy()); order.addProperty("pairIndex", index); } orders.add(order);
    }
    public static List<JsonObject> project(JsonObject view) {
        String symbol = string(view, "symbol"); long time = (long) number(view, "timestamp"); JsonObject market = object(view, "market"), plan = object(view, "plan"), policy = object(view, "policy"), account = object(view, "account");
        List<JsonObject> result = new ArrayList<>();
        if (string(view, "type").equals("market_update")) return vwapPoints(view);
        JsonArray pairs = sortedExitPairs(array(object(account, "exitPairs"), symbol)), entries = array(object(account, "entryOrders"), symbol); JsonObject position = object(object(account, "positions"), symbol); double net = number(position, "netQuantity");
        JsonObject saved = object(object(object(view, "state"), "stateBySymbol"), symbol), active = object(saved, net > 0 ? "breakoutTradeStateForLong" : "breakoutTradeStateForShort"), captured = object(active, "plan");
        JsonObject buttons = msg(view, "trade_button_config"); JsonArray groups = new JsonArray();
        if (plan.has("long") && plan.has("short")) for (JsonElement item : TradingConfig.createTradebookDefinitions(plan)) { JsonObject definition = item.getAsJsonObject(), group = new JsonObject(); String id = string(definition, "tradebookID"); String[] names = NAMES.getOrDefault(id, new String[]{id, id});
            group.addProperty("id", symbol + ":" + id); group.addProperty("label", names[1]); group.addProperty("sideIsLong", bool(definition, "isLong")); group.addProperty("tradebookId", id); group.addProperty("tradebookName", names[0]); JsonArray methods = new JsonArray(); methods.add("1 R"); methods.add("0.5 R"); methods.add("0.3 R"); methods.add("0.2 R"); methods.add("0.1 R"); group.add("entryMethods", methods); groups.add(group); }
        buttons.add("tradebooks", groups); result.add(buttons);
        JsonObject keys = marketLevels(view); JsonArray levels = keys.getAsJsonArray("levels"), zones = keys.getAsJsonArray("zones"); Set<Double> seen = new HashSet<>(); Set<String> zoneKeys = new HashSet<>();
        for (JsonElement item : array(object(plan, "keyLevels"), "otherLevels")) { JsonObject level = item.getAsJsonObject(); double price = number(level, "price"); if (price > 0 && seen.add(price)) levels.add(level.deepCopy()); }
        for (JsonElement item : array(object(plan, "keyLevels"), "zones")) zone(zones, zoneKeys, item.getAsJsonObject(), "", "");
        zone(zones, zoneKeys, object(object(plan, "rangeBoundReversalPlan"), "support"), "support", "green"); zone(zones, zoneKeys, object(object(plan, "rangeBoundReversalPlan"), "resistance"), "resistance", "red");
        zone(zones, zoneKeys, object(object(object(plan, "long"), "gapAndGoPlan"), "support"), "gap & go support", "green"); zone(zones, zoneKeys, object(object(object(plan, "long"), "gapDownAndGoUpPlan"), "support"), "gap down & go up support", "green");
        zone(zones, zoneKeys, object(object(object(plan, "short"), "gapAndCrapPlan"), "resistance"), "gap & crap resistance", "red"); zone(zones, zoneKeys, object(object(object(plan, "short"), "gapDownAndGoDownPlan"), "resistance"), "gap down & go down resistance", "red");
        keys.add("levels", levels); keys.add("zones", zones); keys.addProperty("waitForBidRetest", mode(object(plan, "analysis"), "waitForBidRetest")); keys.addProperty("waitForOfferRetest", mode(object(plan, "analysis"), "waitForOfferRetest"));
        result.add(keys);
        JsonObject exits = msg(view, "exit_order_pairs_config"); JsonArray indexed = pairs.deepCopy(); for (int i = 0; i < indexed.size(); i++) indexed.get(i).getAsJsonObject().addProperty("index", i + 1); exits.add("pairs", indexed); result.add(exits);
        JsonObject accountView = msg(view, "account_state"); JsonArray open = new JsonArray(); entries.forEach(item -> order(open, item.getAsJsonObject(), "ENTRY", null, 0)); for (int i = 0; i < pairs.size(); i++) { JsonObject pair = pairs.get(i).getAsJsonObject(); order(open, object(pair, "STOP"), "STOP", pair, i + 1); order(open, object(pair, "LIMIT"), "LIMIT", pair, i + 1); }
        double risk = positionRisk(net, number(position, "averagePrice"), pairs, number(market, "lowOfDay"), number(market, "highOfDay")), multiple = Math.round(risk / (number(policy, "riskDollars") > 0 ? number(policy, "riskDollars") : 1000) * 1000) / 1000.0;
        if (net != 0 && number(position, "averagePrice") > 0) { JsonObject pos = position.deepCopy(); pos.addProperty("symbol", symbol); pos.addProperty("riskPercent", multiple * 100 > 2 ? Math.round(multiple * 100) : Math.round(multiple * 1000) / 10.0);
            double label = multiple >= 10 ? Math.round(multiple) : Math.round(multiple * 100) / 100.0; pos.addProperty("riskText", multiple > 0 ? (net > 0 ? "+" : "-") + java.math.BigDecimal.valueOf(label).stripTrailingZeros().toPlainString() + "R" : ""); accountView.add("position", pos); }
        accountView.add("openOrders", open); JsonArray fills = array(object(account, "executions"), symbol).deepCopy(); fills.forEach(item -> item.getAsJsonObject().addProperty("timeMs", number(item.getAsJsonObject(), "timestamp"))); accountView.add("executions", fills); result.add(accountView);
        JsonObject core = msg(view, "core_plan_config"); boolean has = net != 0 && bool(policy, "coreTargetEnabled") && bool(active, "hasValue"); core.addProperty("hasActiveTrade", has); core.addProperty("reminderRequested", bool(view, "reminderRequested")); for (String field : new String[]{"requestId", "updateStatus", "error"}) core.addProperty(field, string(view, field));
        if (has) { JsonObject submit = object(active, "submitTime"); long submitMs = (long) (number(submit, "seconds") * 1000 + number(submit, "nanoseconds") / 1000000); double count = number(object(captured, "planConfigs"), "sizingCount"); if (count <= 0) count = number(policy, "batchCount") > 0 ? number(policy, "batchCount") : 10; double exited = 0;
            for (JsonElement item : fills) if (!bool(item.getAsJsonObject(), "positionEffectIsOpen") && number(item.getAsJsonObject(), "timestamp") >= submitMs) exited += number(item.getAsJsonObject(), "quantity");
            core.addProperty("isLong", net > 0); core.addProperty("entryPrice", number(active, "entryPrice")); core.addProperty("coreTarget", number(captured, "coreTarget")); core.addProperty("coreCount", number(captured, "coreCount")); core.addProperty("runnerCondition", string(captured, "runnerTriggerCondition")); core.addProperty("runnerCount", number(captured, "runnerCount")); core.addProperty("corePlan", string(plan, "corePlan")); core.addProperty("bufferedTarget", number(active, "entryPrice") + .9 * (number(captured, "coreTarget") - number(active, "entryPrice"))); core.addProperty("partialsTaken", Workflows.completedPartials(number(active, "initialQuantity"), exited, pairs.size(), count)); core.addProperty("tradeId", symbol + ":" + (net > 0 ? "long" : "short") + ":" + submitMs); }
        result.add(core); result.addAll(vwapPoints(view)); return result;
    }
    private static JsonObject marketLevels(JsonObject view) {
        JsonObject market = object(view, "market"), keys = msg(view, "key_levels_config");
        keys.add("levels", new JsonArray()); keys.add("zones", new JsonArray());
        JsonArray daily = array(object(view, "history"), "dailyBars");
        if (daily.size() > 0) {
            JsonObject previous = daily.get(daily.size() - 1).getAsJsonObject(), day = new JsonObject();
            day.addProperty("high", number(previous, "high")); day.addProperty("low", number(previous, "low"));
            keys.add("previousDay", day);
            keys.add("camPivots", Levels.calculateCamPivots(number(previous, "high"), number(previous, "low"), number(previous, "close")));
        }
        JsonObject pre = new JsonObject(); pre.addProperty("high", number(market, "premarketHigh")); pre.addProperty("low", number(market, "premarketLow")); keys.add("premarket", pre);
        return keys;
    }
    /** Lightweight refresh used for live Massive updates without replacing plan levels or pivots. */
    public static JsonObject premarketLevels(JsonObject view) {
        JsonObject market = object(view, "market"), result = msg(view, "premarket_levels_update"), pre = new JsonObject();
        pre.addProperty("high", number(market, "premarketHigh"));
        pre.addProperty("low", number(market, "premarketLow"));
        result.add("premarket", pre);
        return result;
    }
    private static List<JsonObject> vwapPoints(JsonObject view) {
        JsonObject market = object(view, "market"); JsonArray points = array(market, "vwaps"); if (!market.has("vwaps") && market.has("closedVwap")) { points = new JsonArray(); points.add(market.get("closedVwap")); }
        List<JsonObject> result = new ArrayList<>(); for (JsonElement item : points) { JsonObject point = item.getAsJsonObject(); long effective = (long) number(point, "datetime") + 60000;
            if (number(point, "value") > 0 && effective <= number(view, "timestamp")) { JsonObject value = msg(view, "vwap_update"); value.addProperty("vwap", number(point, "value")); value.addProperty("effectiveTimeMs", effective); value.addProperty("sentAtMs", number(view, "timestamp")); result.add(value); }
        } return result;
    }
}
