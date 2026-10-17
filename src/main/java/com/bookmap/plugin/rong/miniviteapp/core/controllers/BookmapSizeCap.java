package com.bookmap.plugin.rong.miniviteapp.core.controllers;

import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.bookmap.plugin.rong.miniviteapp.models.Models.*;
import com.google.gson.*;
import java.util.*;

/** Final opening-order ceiling, after every entry rule and before any broker effects. */
public final class BookmapSizeCap {
    private BookmapSizeCap() { }
    public enum Mode {
        STRICT("Strict - block below threshold"), PROPORTIONAL("Proportional - cap R");
        private final String label;
        Mode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public static Mode mode(JsonObject cap) {
        return cap.has("mode") ? Mode.valueOf(Models.string(cap, "mode")) : Mode.STRICT;
    }

    /** Expand only the new entry's qualification range, never the actual observed session range. */
    public static JsonObject forEntry(JsonObject cap, JsonObject order) {
        String type = Models.string(order, "orderType");
        if (!type.equals("STOP") && !type.equals("STOP_LIMIT")) return cap;
        boolean isLong = instruction(order).equals("BUY");
        double trigger = Models.number(order, "stopPrice"), low = Models.number(cap, "rangeLow"), high = Models.number(cap, "rangeHigh");
        if (!Models.positive(trigger) || !Models.positive(low) || !Models.positive(high)
                || low > high || (isLong ? trigger <= high : trigger >= low)) return cap;
        String key = isLong ? "upperStopOrders" : "lowerStopOrders";
        if (!cap.has(key)) return cap;
        double largest = Models.number(cap, "largestOrderSize"), required = Models.number(cap, "requiredOrderSize");
        Models.require(Double.isFinite(largest) && largest >= 0 && Models.positive(required), "Bookmap liquidity sizing inputs unavailable");
        JsonObject result = cap;
        for (JsonElement item : cap.getAsJsonArray(key)) {
            JsonObject candidate = item.getAsJsonObject();
            double price = Models.number(candidate, "price"), size = Models.number(candidate, "size");
            boolean within = isLong ? price >= low - 1e-8 && price <= trigger + 1e-8 : price >= trigger - 1e-8 && price <= high + 1e-8;
            if (Models.positive(price) && Models.positive(size) && within && size > largest) {
                if (result == cap) result = cap.deepCopy();
                largest = size;
                result.addProperty("largestOrderSize", size); result.addProperty("maxRiskMultiple", size / required);
                result.addProperty("largestOrderPrice", price);
                if (candidate.has("side")) result.add("largestOrderSide", candidate.get("side"));
                if (candidate.has("observedAt")) result.add("largestOrderObservedAt", candidate.get("observedAt"));
                result.addProperty("qualificationStopPrice", trigger);
            }
        }
        return result;
    }

    public static boolean hasOpening(Plan plan) {
        return plan.requests.stream().anyMatch(request -> opening(request.body));
    }

    public static void apply(Snapshot state, Plan plan) {
        if (state.entryContext == null || !state.entryContext.has("bookmapSizeCap")) return;
        List<Request> openings = new ArrayList<>();
        for (Request request : plan.requests) if (opening(request.body)) openings.add(request);
        if (openings.isEmpty()) return; // Exits and stop adjustments are never restricted.
        JsonObject cap = state.entryContext.getAsJsonObject("bookmapSizeCap");
        Models.require(!cap.has("available") || Models.bool(cap, "available"), "Bookmap liquidity session unavailable");
        if (mode(cap) == Mode.STRICT) {
            for (Request request : openings) {
                JsonObject effective = forEntry(cap, request.body);
                double largest = Models.number(effective, "largestOrderSize"), required = Models.number(effective, "requiredOrderSize");
                Models.require(Double.isFinite(largest) && largest >= 0 && Models.positive(required), "Bookmap strict liquidity inputs unavailable");
                Models.require(largest >= required, "Bookmap STRICT: ENTRY BLOCKED; largest qualifying order=" + largest
                        + " shares; required=" + required + " shares (stop entries are checked through their trigger)");
                if (plan.entry != null) plan.entry.add("bookmapSizeCap", effective.deepCopy());
            }
            return;
        }
        // Multiple entries in one action must each support the action's entire shared risk ceiling.
        for (Request request : openings) {
            JsonObject effective = forEntry(state.entryContext.getAsJsonObject("bookmapSizeCap"), request.body);
            if (request == openings.get(0) || Models.number(effective, "maxRiskMultiple") < Models.number(cap, "maxRiskMultiple")) cap = effective;
        }
        double multiple = Models.number(cap, "maxRiskMultiple");
        double unitRisk = Models.number(state.entryContext, "riskDollars");
        Models.require(Double.isFinite(multiple) && multiple >= 0 && Models.positive(unitRisk), "Bookmap size cap unavailable");
        boolean isLong = instruction(openings.get(0).body).equals("BUY");
        double reserved = 0;
        // A swap closes the old position before submitting its reentry.
        if (!plan.action.equals("swap") && state.netQuantity != 0 && (state.netQuantity > 0) == isLong)
            reserved += ExtendedEntryRules.positionRisk(state);
        for (Order pending : state.entries) if (pending.isBuy == isLong) {
            boolean replacing = openings.stream().anyMatch(request -> request.method.equals("PUT") && request.orderId.equals(pending.id));
            if (replacing) continue;
            Models.require(Models.positive(pending.price) && Models.positive(pending.exitStopPrice)
                    && Models.positive(pending.quantity), "pending entry risk unavailable for Bookmap size cap");
            reserved += pending.quantity * Math.abs(pending.price - pending.exitStopPrice);
        }
        double requestedRisk = 0;
        for (Request request : openings) {
            Models.require(instruction(request.body).equals(isLong ? "BUY" : "SELL_SHORT"), "mixed entry sides in Bookmap size cap");
            requestedRisk += quantity(request.body) * perShareRisk(state, plan, request.body, isLong);
        }
        Models.require(Models.positive(requestedRisk), "entry risk unavailable for Bookmap size cap");
        double available = Math.max(0, multiple * unitRisk - reserved);
        if (requestedRisk <= available) return;
        double ratio = available / requestedRisk;
        double submitted = 0;
        for (Request request : openings) {
            long shares = (long) Math.floor(quantity(request.body) * ratio);
            if (shares == 0) { plan.requests.remove(request); continue; }
            resize(request.body, shares);
            submitted += shares;
            if (plan.entry != null) {
                JsonObject result = plan.entry.getAsJsonObject("submitEntryResult");
                result.addProperty("totalQuantity", shares);
                JsonArray targets = new JsonArray();
                for (JsonElement child : request.body.getAsJsonArray("childOrderStrategies")) {
                    JsonObject limit = child.getAsJsonObject().getAsJsonArray("childOrderStrategies").get(1).getAsJsonObject();
                    JsonObject target = new JsonObject(); target.addProperty("target", Models.number(limit, "price"));
                    target.addProperty("quantity", quantity(limit)); targets.add(target);
                }
                result.add("profitTargets", targets);
                plan.entry.add("bookmapSizeCap", cap.deepCopy());
            }
        }
        Models.require(submitted > 0, "Bookmap size cap leaves no whole shares available (cap=" + multiple + "R)");
        plan.warnings.add("Bookmap size cap: " + multiple + "R; opening quantity reduced to " + (long) submitted + " shares");
    }

    private static String instruction(JsonObject order) {
        return Models.string(order.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject(), "instruction");
    }
    private static boolean opening(JsonObject order) {
        if (order == null || !order.has("orderLegCollection")) return false;
        String value = instruction(order); return value.equals("BUY") || value.equals("SELL_SHORT");
    }
    private static double quantity(JsonObject order) {
        return Models.number(order.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject(), "quantity");
    }
    private static void quantity(JsonObject order, long shares) {
        order.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().addProperty("quantity", shares);
    }
    private static double perShareRisk(Snapshot state, Plan plan, JsonObject order, boolean isLong) {
        double price;
        String type = Models.string(order, "orderType");
        if (type.equals("MARKET")) {
            double estimate = plan.entry == null ? state.currentPrice : Models.number(plan.entry, "entryPrice");
            price = isLong ? Math.max(estimate, Math.max(state.currentPrice, state.ask)) + 0.01
                    : Math.min(estimate, Math.min(state.currentPrice, state.bid)) - 0.01;
        } else price = Models.number(order, type.equals("STOP") ? "stopPrice" : "price");
        Models.require(Models.positive(price) && Models.positive(quantity(order)), "invalid opening risk for Bookmap size cap");
        double risk = 0;
        for (JsonElement child : order.getAsJsonArray("childOrderStrategies")) {
            JsonObject stop = child.getAsJsonObject().getAsJsonArray("childOrderStrategies").get(0).getAsJsonObject();
            double stopPrice = Models.number(stop, "stopPrice");
            Models.require(Models.positive(stopPrice), "protective stop unavailable for Bookmap size cap");
            risk = Math.max(risk, Math.abs(price - stopPrice));
        }
        return risk;
    }
    private static void resize(JsonObject order, long shares) {
        double original = quantity(order), cumulative = 0;
        long assigned = 0;
        JsonArray children = new JsonArray();
        JsonArray originalChildren = order.getAsJsonArray("childOrderStrategies");
        for (int i = 0; i < originalChildren.size(); i++) {
            JsonObject child = originalChildren.get(i).getAsJsonObject();
            JsonArray legs = child.getAsJsonArray("childOrderStrategies");
            cumulative += quantity(legs.get(0).getAsJsonObject());
            long end = i == originalChildren.size() - 1 ? shares : (long) Math.floor(shares * cumulative / original);
            long size = end - assigned; assigned = end;
            if (size == 0) continue;
            for (JsonElement leg : legs) quantity(leg.getAsJsonObject(), size);
            children.add(child);
        }
        quantity(order, shares); order.add("childOrderStrategies", children);
    }
}
