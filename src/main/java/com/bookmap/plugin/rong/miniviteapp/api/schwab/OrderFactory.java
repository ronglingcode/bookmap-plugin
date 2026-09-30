package com.bookmap.plugin.rong.miniviteapp.api.schwab;

import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Equity mirror of api/schwab/orderFactory.ts. */
public final class OrderFactory {
    private OrderFactory() { }
    public static JsonObject createSingleOrder(String symbol, String type, double quantity, double price, boolean isBuy) {
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
            order.addProperty(type.equals("STOP") ? "stopPrice" : "price", price);
        }
        return order;
    }
    public static JsonObject createOneEntryWithMultipleExits(String symbol, boolean isLong, String type,
            double quantity, double price, JsonArray targets, double stop) {
        JsonObject entry = createSingleOrder(symbol, type, quantity, price, !isLong);
        entry.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().addProperty("instruction", isLong ? "BUY" : "SELL_SHORT");
        entry.addProperty("orderStrategyType", "TRIGGER"); JsonArray children = new JsonArray();
        for (var element : targets) {
            var target = element.getAsJsonObject(); double shares = Models.number(target, "quantity");
            JsonObject oco = new JsonObject(); oco.addProperty("orderStrategyType", "OCO");
            JsonArray legs = new JsonArray();
            legs.add(createSingleOrder(symbol, "STOP", shares, stop, !isLong));
            legs.add(createSingleOrder(symbol, "LIMIT", shares, Models.number(target, "target"), !isLong));
            oco.add("childOrderStrategies", legs); children.add(oco);
        }
        Models.require(!children.isEmpty(), "entry requires protective brackets");
        entry.add("childOrderStrategies", children); return entry;
    }
    public static JsonObject createOneEntryWithTwoExits(String symbol, boolean isLong, String type,
            double quantity, double price, double target, double stop) {
        JsonObject profit = new JsonObject(); profit.addProperty("target", target); profit.addProperty("quantity", quantity);
        JsonArray targets = new JsonArray(); targets.add(profit);
        return createOneEntryWithMultipleExits(symbol, isLong, type, quantity, price, targets, stop);
    }
}
