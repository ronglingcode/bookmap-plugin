package com.bookmap.plugin.rong.miniviteapp.core.controllers;

import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.bookmap.plugin.rong.miniviteapp.models.Models.Snapshot;
import com.google.gson.JsonObject;

/** Mirrors riskManager's existing-position sizing and entryRulesChecker's partial rules. */
public final class ExtendedEntryRules {
    private ExtendedEntryRules() { }

    public static double positionRisk(Snapshot state) {
        if (state.netQuantity == 0) return 0;
        Models.require(Models.positive(state.averagePrice), "position average price unavailable");
        double quantity = 0, risk = 0;
        for (var pair : state.pairs) if (pair.stop != null && Models.positive(pair.stop.price)) {
            quantity += pair.stop.quantity;
            risk += pair.stop.quantity * Math.abs(state.averagePrice - pair.stop.price);
        }
        if (quantity < Math.abs(state.netQuantity)) {
            double fallback = Models.number(state.entryContext, state.netQuantity > 0 ? "lowOfDay" : "highOfDay");
            Models.require(Models.positive(fallback), "position risk day level unavailable");
            risk += (Math.abs(state.netQuantity) - quantity) * Math.abs(state.averagePrice - fallback);
        }
        return risk;
    }

    public static double entryRisk(Snapshot state) {
        double risk = 0;
        for (var entry : state.entries) if (Models.positive(entry.price) && Models.positive(entry.exitStopPrice))
            risk += entry.quantity * Math.abs(entry.price - entry.exitStopPrice);
        return risk;
    }

    private static double multiples(double dollars, double riskDollars) {
        return Math.round(dollars / riskDollars * 1000) / 1000.0;
    }

    public static double nextEntryMultiplier(Snapshot state, boolean isLong, double entryPrice) {
        if (state.netQuantity == 0 || (state.netQuantity > 0) != isLong) return 1;
        JsonObject context = state.entryContext;
        double threshold = Models.number(context, isLong ? "addTargetLong" : "addTargetShort");
        if (threshold < 0) {
            boolean gapUp = Models.bool(context, "isGappedUp");
            threshold = Models.number(context, isLong ? (gapUp ? "premarketHigh" : "vwap")
                    : (gapUp ? "vwap" : "premarketLow"));
        }
        Models.require(Double.isFinite(threshold), "heavier-position threshold unavailable");
        if (isLong ? entryPrice >= threshold : entryPrice <= threshold) return 1;
        double riskDollars = Models.number(context, "riskDollars");
        double cap = Models.number(context, "maxRiskMultipleWithExistingPosition");
        Models.require(Models.positive(riskDollars) && Models.positive(cap), "existing-position sizing inputs unavailable");
        double available = multiples(cap * riskDollars - positionRisk(state), riskDollars);
        if (available >= 1) return 1;
        if (available >= 0.1) return 0.1;
        throw new IllegalArgumentException("existing position reaches risk cap");
    }

    public static void checkPartial(Snapshot state, JsonObject direction, boolean isLong,
            double quantity, double price, double stop) {
        JsonObject context = state.entryContext;
        double currentRisk = positionRisk(state), threshold = Models.number(context, "allowAddIfBelow");
        Models.require(Double.isFinite(threshold), "low-risk add threshold unavailable");
        // canReloadPartial applies the tradebook boundary before this override.
        if (state.netQuantity != 0 && (state.netQuantity > 0) == isLong && currentRisk < threshold) return;
        Models.require(Models.bool(context, "attendanceAllowed"), "attendance blocks partial entry");
        EntryRulesChecker.checkEligibility(context);
        double pnl = Models.number(context, "realizedPnl"), limit = Models.number(context, "dailyMaxLoss");
        Models.require(Double.isFinite(pnl) && Models.positive(limit), "partial risk inputs unavailable");
        Models.require(pnl - currentRisk >= -limit && pnl > -limit, "checkRule: daily loss limit blocks partial entry; realized P&L=" + pnl + ", existing position risk=" + currentRisk + ", daily max loss=" + limit);
        double totalRisk = currentRisk + entryRisk(state), addedRisk = quantity * Math.abs(price - stop);
        Models.require(!(pnl < 0 && -pnl + addedRisk + totalRisk > limit), "checkRule: partial entry exceeds daily loss budget; realized P&L=" + pnl + ", existing risk=" + totalRisk + ", added risk=" + addedRisk + ", daily max loss=" + limit);
        double todayRange = Models.number(context, "todayRange");
        if (Models.number(direction, "addCount") > 2 && todayRange != 0 && Models.number(context, "secondsSinceMarketOpen") >= 0) {
            Models.require(Double.isFinite(todayRange), "daily-range add input unavailable");
            double dayStop = Models.number(context, isLong ? "lowOfDay" : "highOfDay");
            Models.require(Math.abs(price - dayStop) / todayRange < 0.5, "checkRule: partial entry exceeds half daily range; entry=" + price + ", day stop=" + dayStop + ", daily range=" + todayRange);
        }
        Models.require(state.netQuantity == 0 || (totalRisk + addedRisk) / limit <= 0.52, "checkRule: partial entry exceeds position risk budget; total risk=" + (totalRisk + addedRisk) + ", limit=" + limit * 0.52);
        Models.require(!Models.string(direction, "stopTightenPhase").equals("needs_tighten"), "tighten stop before adding");
    }
}
