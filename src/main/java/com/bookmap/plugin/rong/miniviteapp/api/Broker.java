package com.bookmap.plugin.rong.miniviteapp.api;

import com.bookmap.plugin.rong.miniviteapp.api.schwab.OrderFactory;
import com.bookmap.plugin.rong.miniviteapp.models.Models.*;

/** Mirror: api/broker.ts cancelOrders, instantOutOneExitPair and replaceExitPairWithNewPrice. */
public final class Broker {
    private Broker() { }
    public static void cancelOrders(Plan plan, Order order) { plan.requests.add(new Request("DELETE", order, null)); }
    public static void instantOutOneExitPair(Plan plan, ExitPair pair) {
        Order leg = pair.marketLeg();
        plan.requests.add(new Request("PUT", leg,
                OrderFactory.createSingleOrder(leg.symbol, "MARKET", leg.quantity, 0, leg.isBuy)));
    }
    public static void replaceExitPairWithNewPrice(Plan plan, ExitPair pair, double price, boolean stop) {
        var leg = stop ? pair.stop : pair.limit;
        com.bookmap.plugin.rong.miniviteapp.models.Models.require(pair.stop != null && pair.limit != null,
                "both exit legs required for adjustment");
        plan.requests.add(new Request("PUT", leg,
                OrderFactory.createSingleOrder(leg.symbol, leg.type, leg.quantity, price, leg.isBuy)));
    }
}
