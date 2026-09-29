package com.bookmap.plugin.rong.miniviteapp.api.schwab;

import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Closing-only mirror of api/schwab/orderFactory.ts#createSingleOrder. */
public final class OrderFactory {
    private OrderFactory() { }
    public static JsonObject createSingleOrder(String symbol, String type, double quantity, double price, boolean isBuy) {
        Models.require(Models.shares(quantity), "invalid closing quantity");
        Models.require(type.equals("MARKET") || type.equals("STOP") || type.equals("LIMIT"), "invalid closing type");
        JsonObject instrument = new JsonObject();
        instrument.addProperty("assetType", "EQUITY"); instrument.addProperty("symbol", symbol);
        JsonObject leg = new JsonObject();
        leg.addProperty("orderLegType", "EQUITY"); leg.add("instrument", instrument);
        leg.addProperty("instruction", isBuy ? "BUY_TO_COVER" : "SELL"); leg.addProperty("quantity", quantity);
        JsonArray legs = new JsonArray(); legs.add(leg);
        JsonObject order = new JsonObject();
        order.addProperty("session", "NORMAL"); order.addProperty("duration", "DAY");
        order.add("orderLegCollection", legs); order.addProperty("orderType", type);
        order.addProperty("orderStrategyType", "SINGLE");
        if (!type.equals("MARKET")) {
            Models.require(Models.positive(price), "invalid closing price");
            order.addProperty(type.equals("STOP") ? "stopPrice" : "price", price);
        }
        return order;
    }
}
