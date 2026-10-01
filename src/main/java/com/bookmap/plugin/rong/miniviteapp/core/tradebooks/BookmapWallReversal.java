package com.bookmap.plugin.rong.miniviteapp.core.tradebooks;

import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.google.gson.JsonObject;
import java.util.Map;

public final class BookmapWallReversal {
    private BookmapWallReversal() { }
    private static final Map<String, Boolean> SIDES = Map.of(
        "GapGiveAndGoBookmapReversal", true, "GapDownAndGoUpBookmapReversal", true,
        "RangeBoundBidReversal", true, "RangeBoundOfferReversal", false,
        "GapAndCrapOfferStepDownReappear", false, "GapAndCrapBreakdownBidSwingLow", false,
        "GapDownAndGoDownOfferStepDownReappear", false, "GapDownAndGoDownBreakdownBidSwingLow", false);
    public static boolean supports(String id) { return SIDES.containsKey(id); }
    public static boolean side(String id) {
        Models.require(supports(id), "unsupported entry tradebook"); return SIDES.get(id);
    }
    public static void checkEntryPrice(JsonObject definition, double price, boolean isLong) {
        var area = definition.getAsJsonObject("entryArea");
        double a = Models.number(area, "low"), b = Models.number(area, "high");
        Models.require(Double.isFinite(a) && Double.isFinite(b), "invalid entry area");
        if (area.has("requireEntryWithinRange")) Models.require(area.get("requireEntryWithinRange").getAsJsonPrimitive().isBoolean(), "invalid entry range flag");
        double low = Math.min(a, b), high = Math.max(a, b);
        boolean allowed = Models.bool(area, "requireEntryWithinRange") ? price >= low && price <= high : isLong ? price >= low : price <= high;
        Models.require(allowed, "entry outside tradebook boundary");
    }
}
