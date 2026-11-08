package com.bookmap.plugin.rong.miniviteapp.core.controllers;

import com.bookmap.plugin.rong.miniviteapp.core.algorithms.EntrySizing;
import com.bookmap.plugin.rong.miniviteapp.core.tradebooks.BookmapWallReversal;
import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.bookmap.plugin.rong.miniviteapp.models.Models.Snapshot;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import static com.bookmap.plugin.rong.miniviteapp.models.DomainJson.*;

/** Flat chart digits select an ordered share button and use hover as the stop. */
public final class HoverShareEntry {
    private HoverShareEntry() { }
    public static boolean isChartDigit(JsonObject action, String key) {
        return key.matches("Digit[1-5]") && string(action, "source").equals("bookmap_chart_hotkey");
    }
    public static JsonObject prepare(Snapshot state, JsonObject action, String key) {
        Models.require(state.netQuantity == 0 && state.pairs.isEmpty(), "Share hotkey requires a flat position without exit pairs");
        Models.require(state.entries.isEmpty(), "Entry already pending; cancel it before using a share hotkey");
        Models.require(state.entryContext != null, "entry context unavailable");
        Models.require(!action.has("priceUnit") || string(action, "priceUnit").equals("real"), "unsupported stop price unit");
        double stop = Math.round(number(action, "price") * 100) / 100.0;
        double bid = action.has("hoverEntryBid") ? number(action, "hoverEntryBid") : state.bid;
        double ask = action.has("hoverEntryAsk") ? number(action, "hoverEntryAsk") : state.ask;
        Models.require(Models.positive(stop) && Models.positive(bid) && Models.positive(ask) && bid < ask,
                "Hover stop and current bid/ask are required for a share hotkey");
        Models.require(stop < bid || stop > ask, "Hover stop must be below bid for long or above ask for short");
        boolean isLong = stop < bid;
        List<String> shares = new ArrayList<>();
        for (var method : EntrySizing.entryMethods(array(state.entryContext, "fixedShares")))
            if (EntrySizing.fixedShares(method.getAsString()) > 0) shares.add(method.getAsString());
        int index = key.charAt(5) - '1';
        Models.require(index < shares.size(), "No fixed-share button " + (index + 1) + " configured for " + state.symbol);
        JsonObject definition = null;
        for (var item : array(state.entryContext, "definitions")) {
            JsonObject candidate = item.getAsJsonObject();
            if (!bool(candidate, "enabled") || bool(candidate, "isLong") != isLong
                    || !BookmapWallReversal.supports(string(candidate, "tradebookID"))) continue;
            Models.require(definition == null, "Multiple entry tradebooks for this side; use the intended button");
            definition = candidate;
        }
        Models.require(definition != null, "No enabled " + (isLong ? "long" : "short") + " entry tradebook");
        JsonObject entry = action.deepCopy();
        entry.addProperty("keyCode", ""); entry.addProperty("key_code", "");
        entry.addProperty("tradebook_id", string(definition, "tradebookID"));
        entry.addProperty("entry_method", shares.get(index));
        entry.addProperty("use_market_order", true);
        entry.addProperty("sideIsLong", isLong);
        entry.addProperty("estimated_entry_price", isLong ? ask : bid);
        entry.addProperty("retest_blocked", bool(action, "retest_blocked")
                || bool(action, isLong ? "hoverLongRetestBlocked" : "hoverShortRetestBlocked"));
        entry.addProperty("hoverStopPrice", stop);
        entry.addProperty("button_name", "Chart " + key.substring(5) + ": " + shares.get(index) + " market " + (isLong ? "long" : "short"));
        return entry;
    }
}
