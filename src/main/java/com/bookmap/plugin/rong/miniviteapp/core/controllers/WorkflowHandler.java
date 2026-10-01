package com.bookmap.plugin.rong.miniviteapp.core.controllers;

import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.bookmap.plugin.rong.miniviteapp.models.Models.*;
import com.bookmap.plugin.rong.miniviteapp.libraries.broker.Broker;
import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.OrderFactory;
import com.google.gson.*;
import static com.bookmap.plugin.rong.miniviteapp.models.DomainJson.*;

/** Additional manual/periodic workflows. Decisions mirror TS core, requests remain explicit. */
public final class WorkflowHandler {
    private WorkflowHandler() { }
    public static boolean supports(String key) { return key.equals("KeyQ") || key.equals("KeyP") || key.equals("KeyJ") || key.equals("KeyK") || key.equals("KeyL") || key.equals("RefreshEntryStop"); }
    public static Plan handle(Snapshot state, String key, boolean shift) {
        if (key.equals("KeyQ")) { Plan plan = new Plan("cancel_breakout_entries"); plan.clearPending = true; state.entries.stream().filter(order -> order.type.equals("STOP")).forEach(order -> Broker.cancelOrders(plan, order)); return plan; }
        Models.require(state.entryContext != null, "workflow inputs unavailable");
        JsonObject active = object(state.entryContext, "activeTrade");
        if (key.equals("RefreshEntryStop")) {
            Models.require(state.entries.size() == 1 && state.pairs.isEmpty(), "pending entry changed before refresh");
            Order existing = state.entries.get(0); boolean isLong = existing.isBuy; double stop = number(state.entryContext, isLong ? "lowOfDay" : "highOfDay");
            Models.require(Models.positive(stop) && (isLong ? stop < existing.exitStopPrice : stop > existing.exitStopPrice), "pending stop does not need widening");
            JsonObject saved = object(state.entryContext, isLong ? "longTrade" : "shortTrade");
            Models.require(bool(saved, "hasValue"), "pending entry has no captured trading state");
            JsonObject definition = new JsonObject(); definition.addProperty("tradebookID", string(object(saved, "submitEntryResult"), "tradeBookID")); definition.add("basePlan", object(saved, "plan"));
            double entry = isLong ? Math.max(existing.price, state.ask) : Math.min(existing.price, state.bid);
            Models.require(Models.positive(entry) && (isLong ? stop < entry : stop > entry), "invalid pending replacement prices");
            Plan sized = OrderFlow.submitEntry(state, state.entryContext, definition, new JsonObject(), isLong, false, entry, stop, number(saved, "sizeMultipler"), state.batchCount, "");
            Plan plan = new Plan("refresh_pending_entry"); plan.entry = sized.entry; plan.warnings.addAll(sized.warnings); plan.requests.add(new Request("PUT", existing, sized.requests.get(0).body)); return plan;
        }
        Models.require(state.netQuantity != 0, "workflow requires an open position");
        if (key.equals("KeyP")) {
            Models.require(bool(active, "hasValue"), "profit reset requires captured trade state");
            JsonArray targets = Workflows.profitResetTargets(array(object(active, "submitEntryResult"), "profitTargets"), Math.abs(state.netQuantity));
            double stop = number(active, "stopLossPrice"); Models.require(Models.positive(stop), "profit reset protective stop unavailable");
            Plan plan = new Plan("reset_profit_targets");
            for (ExitPair pair : state.pairs) { Models.require(pair.marketLeg().isBuy == (state.netQuantity < 0), "exit side disagrees with position"); if (pair.limit != null) Broker.cancelOrders(plan, pair.limit); if (pair.stop != null) Broker.cancelOrders(plan, pair.stop); }
            boolean first = true; for (JsonElement item : targets) {
                JsonObject target = item.getAsJsonObject(); double price = number(target, "target"); Models.require(state.netQuantity > 0 ? price > stop : price < stop, "profit target crosses protective stop");
                JsonObject oco = new JsonObject(); oco.addProperty("orderStrategyType", "OCO"); JsonArray children = new JsonArray();
                children.add(OrderFactory.createSingleOrder(state.symbol, "STOP", number(target, "quantity"), stop, state.netQuantity < 0)); children.add(OrderFactory.createSingleOrder(state.symbol, "LIMIT", number(target, "quantity"), price, state.netQuantity < 0)); oco.add("childOrderStrategies", children);
                Request request = new Request("POST", null, oco); request.delayBeforeMs = first ? 800 : 0; first = false; plan.requests.add(request);
            }
            return plan;
        }
        Models.require(!state.pairs.isEmpty(), "no exit pairs for trailing stop");
        int timeframe = key.equals("KeyJ") ? 5 : key.equals("KeyK") ? 15 : 30;
        double price = Workflows.trailStopPrice(array(state.entryContext, "candles"), state.netQuantity > 0, timeframe, shift);
        Plan plan = new Plan(shift ? "trail_market_partial" : "trail_stop_partial");
        for (ExitPair pair : state.pairs) if (pair.stop != null && pair.stop.price != price) {
            Models.require(pair.stop.isBuy == (state.netQuantity < 0), "exit side disagrees with position");
            if (shift) { CoreTargetExitRules.check(state, pair, state.currentPrice, true); Broker.instantOutOneExitPair(plan, pair); }
            else { boolean isLong = state.netQuantity > 0; double clamped = isLong ? Math.min(price, state.bid) : Math.max(price, state.ask); Models.require(Models.positive(clamped), "quote unavailable for trailing stop"); CoreTargetExitRules.check(state, pair, price, isLong ? price > pair.stop.price : price < pair.stop.price); CoreTargetExitRules.check(state, pair, clamped, isLong ? clamped > pair.stop.price : clamped < pair.stop.price); plan.requests.add(new Request("PUT", pair.stop, OrderFactory.createSingleOrder(state.symbol, "STOP", pair.stop.quantity, clamped, pair.stop.isBuy))); }
            break;
        }
        return plan;
    }
}
