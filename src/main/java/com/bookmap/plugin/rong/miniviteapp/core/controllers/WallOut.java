package com.bookmap.plugin.rong.miniviteapp.core.controllers;

import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.google.gson.JsonObject;

/** Mirror of trading/core/controllers/wallOut.ts. Prices are real equity prices. */
public final class WallOut {
    public static final int MINIMUM_SIZE = 5_000;
    private WallOut() { }

    public static int count(JsonObject action) {
        String id = Models.string(action, "button_id");
        return id.equals("hotkey:wall_out_1") ? 1 : id.equals("hotkey:wall_out_2") ? 2 : 0;
    }

    public static double targetPrice(boolean isLong, JsonObject snapshot) {
        Models.require(snapshot != null && Models.string(snapshot, "priceUnit").equals("real")
                && Models.positive(Models.number(snapshot, "tickSize")), "Bookmap order book is not ready");
        String side = isLong ? "largeAsks" : "largeBids";
        double nearest = isLong ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        if (snapshot.has(side)) for (var element : snapshot.getAsJsonArray(side)) {
            var level = element.getAsJsonArray();
            double price = level.get(0).getAsDouble(), size = level.get(1).getAsDouble();
            if (Models.positive(price) && Double.isFinite(size) && size >= MINIMUM_SIZE)
                nearest = isLong ? Math.min(nearest, price) : Math.max(nearest, price);
        }
        Models.require(Double.isFinite(nearest), "No " + (isLong ? "offer" : "bid") + " wall of at least 5,000 lots");
        double tick = Models.number(snapshot, "tickSize");
        double offsetTicks = Math.max(1, Math.ceil(0.02 / tick - 1e-9));
        double price = Math.round((nearest + (isLong ? -1 : 1) * offsetTicks * tick) * 100) / 100.0;
        Models.require(Models.positive(price), "Invalid wall-out target price");
        return price;
    }
}
