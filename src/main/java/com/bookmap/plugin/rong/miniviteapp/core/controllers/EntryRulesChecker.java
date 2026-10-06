package com.bookmap.plugin.rong.miniviteapp.core.controllers;

import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.google.gson.JsonObject;

/** Flat-position slice of controllers/entryRulesChecker.ts. */
public final class EntryRulesChecker {
    private EntryRulesChecker() { }
    public static void checkEligibility(JsonObject context) {
        String startup = Models.string(context, "startupBlockReason");
        Models.require(startup.isEmpty(), "checkRule: startup eligibility: " + startup);
        String watchlist = Models.string(context, "watchlistBlockReason");
        Models.require(watchlist.isEmpty(), "checkRule: " + watchlist);
    }
    public static double checkBasicGlobalEntryRules(JsonObject context, boolean isLong, double entryPrice) {
        Models.require(Models.bool(context, "attendanceAllowed"), "checkRule: attendance blocks entry");
        checkEligibility(context);
        double pnl = Models.number(context, "realizedPnl"), maxLoss = Models.number(context, "dailyMaxLoss");
        Models.require(!(pnl < 0 && -pnl >= maxLoss), "checkRule: Daily max loss exceeded; realized P&L=" + pnl + ", daily max loss=" + maxLoss);
        double initialSize = Models.number(context, "liquidityScale");
        Models.require(initialSize != 0, "checkRule: post-open liquidity blocks entry; liquidity scale=" + initialSize + "; regular-session volume/dollar thresholds not met");
        // shouldAllowEarlyEntry currently returns allowed for all volume quality cases in ViteApp.
        double seconds = Models.number(context, "secondsSinceMarketOpen");
        double open = Models.number(context, "openPrice"), vwap = Models.number(context, "vwap"), atr = Models.number(context, "atr");
        double size = initialSize;
        var watchAreas = context.getAsJsonArray("watchAreas");
        if (!watchAreas.isEmpty()) {
            double level = watchAreas.get(0).getAsDouble();
            Models.require(!nearAgainst(isLong, entryPrice, level, atr), "checkRule: entry price " + entryPrice + " is near against watch level " + level + ", block entry; ATR buffer=" + atr * 0.15);
            Models.require(!(seconds < 60 && open != 0 && nearAgainst(isLong, open, level, atr)), "checkRule: open price " + open + " is near against watch level " + level + ", block entry in first minute; ATR buffer=" + atr * 0.15);
            if (nearAgainst(isLong, entryPrice, vwap, atr) || seconds < 60 && open != 0 && nearAgainst(isLong, open, vwap, atr)) size = initialSize * 0.5;
        }
        for (var element : context.getAsJsonArray("noTradeZones")) {
            var zone = element.getAsJsonObject();
            double low = Models.number(zone, "low"), high = Models.number(zone, "high");
            Models.require(!(low < entryPrice && high > entryPrice), "checkRule: entry price " + entryPrice + " is inside no trade zone " + low + " - " + high + ", block entry");
        }
        var volumes = context.getAsJsonArray("volumes");
        if (volumes.size() >= 3) {
            int maxIndex = 0, lastClosed = volumes.size() - 2;
            for (int i = 1; i <= lastClosed; i++) if (volumes.get(i).getAsDouble() > volumes.get(maxIndex).getAsDouble()) maxIndex = i;
            int start = maxIndex + 1 <= lastClosed ? maxIndex + 1 : maxIndex;
            double max = volumes.get(start).getAsDouble();
            for (int i = start; i < volumes.size(); i++) max = Math.max(max, volumes.get(i).getAsDouble());
            if (max < 150_000) size = initialSize * 0.5;
        }
        return size;
    }
    private static boolean nearAgainst(boolean isLong, double price, double level, double atr) {
        return isLong ? price < level && price >= level - atr * 0.15 : price > level && price <= level + atr * 0.15;
    }
}
