package com.bookmap.plugin.rong.miniviteapp.algorithms;

import com.bookmap.plugin.rong.miniviteapp.models.Models;

/** Equity slice of algorithms/riskManager.ts. */
public final class RiskManager {
    private RiskManager() { }
    public static double addCents(double price) { return (Math.ceil(price * 100) + 1) / 100; }
    public static double minusCents(double price) { return (Math.floor(price * 100) - 1) / 100; }
    public static double calculateTotalShares(double entryPrice, double riskPrice, double multiplier, double riskDollars) {
        double risk = Math.abs(entryPrice - riskPrice);
        Models.require(Models.positive(risk) && Models.positive(multiplier) && Models.positive(riskDollars), "invalid risk sizing inputs");
        return Math.max(2, Math.floor(multiplier * riskDollars / risk));
    }
}
