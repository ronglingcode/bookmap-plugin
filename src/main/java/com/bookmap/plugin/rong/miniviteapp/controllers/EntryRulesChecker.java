package com.bookmap.plugin.rong.miniviteapp.controllers;

import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.google.gson.JsonObject;

/** Flat-position slice of controllers/entryRulesChecker.ts. */
public final class EntryRulesChecker {
    private EntryRulesChecker() { }
    public static double checkBasicGlobalEntryRules(JsonObject context, boolean isLong, double entryPrice) {
        Models.require(Models.bool(context, "attendanceAllowed"), "attendance blocks entry");
        Models.require(Models.string(context, "watchlistBlockReason").isEmpty(), "watchlist limit blocks entry");
        double pnl = Models.number(context, "realizedPnl"), maxLoss = Models.number(context, "dailyMaxLoss");
        Models.require(Double.isFinite(pnl) && Models.positive(maxLoss) && pnl > -maxLoss, "daily loss limit blocks entry");
        double initialSize = Models.number(context, "liquidityScale");
        Models.require(Models.positive(initialSize) && initialSize <= 1, "liquidity blocks entry");
        // shouldAllowEarlyEntry currently returns allowed for all volume quality cases in ViteApp.
        double seconds = Models.number(context, "secondsSinceMarketOpen");
        Models.require(Double.isFinite(seconds) && seconds > 0 && seconds < 6.5 * 3600, "regular market session required");
        double open = Models.number(context, "openPrice"), vwap = Models.number(context, "vwap"), atr = Models.number(context, "atr");
        Models.require(Double.isFinite(open) && Double.isFinite(vwap) && Double.isFinite(atr) && atr >= 0, "missing entry rule inputs");
        double size = initialSize;
        var watchAreas = context.getAsJsonArray("watchAreas");
        if (!watchAreas.isEmpty()) {
            double level = watchAreas.get(0).getAsDouble();
            Models.require(Double.isFinite(level), "invalid watch area");
            Models.require(!nearAgainst(isLong, entryPrice, level, atr), "entry near opposing watch level");
            Models.require(!(seconds < 60 && open != 0 && nearAgainst(isLong, open, level, atr)), "open near opposing watch level");
            if (nearAgainst(isLong, entryPrice, vwap, atr) || seconds < 60 && open != 0 && nearAgainst(isLong, open, vwap, atr)) size = initialSize * 0.5;
        }
        for (var element : context.getAsJsonArray("noTradeZones")) {
            var zone = element.getAsJsonObject();
            double low = Models.number(zone, "low"), high = Models.number(zone, "high");
            Models.require(Double.isFinite(low) && Double.isFinite(high), "invalid no-trade zone");
            Models.require(!(low < entryPrice && high > entryPrice), "entry inside no-trade zone");
        }
        var volumes = context.getAsJsonArray("volumes");
        for (var volume : volumes) Models.require(Double.isFinite(volume.getAsDouble()) && volume.getAsDouble() >= 0, "invalid volume input");
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
