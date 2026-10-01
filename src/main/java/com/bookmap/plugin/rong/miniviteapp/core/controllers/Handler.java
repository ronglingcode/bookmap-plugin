package com.bookmap.plugin.rong.miniviteapp.core.controllers;

import com.bookmap.plugin.rong.miniviteapp.libraries.broker.Broker;
import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.OrderFactory;
import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.bookmap.plugin.rong.miniviteapp.models.Models.*;
import com.bookmap.plugin.rong.miniviteapp.core.utils.ExitPairSelection;
import java.util.List;

/** Mirror of controllers/handler.ts. Plans are pure; HTTP/state effects live outside this class. */
public final class Handler {
    private Handler() { }
    public static Plan cancelKeyPressed(Snapshot state) {
        Plan plan = new Plan("cancel_pending_entries"); plan.clearPending = true;
        boolean all = state.pairs.size() < state.batchCount * 0.4;
        state.entries.stream().filter(order -> all || order.type.equals("STOP"))
                .forEach(order -> Broker.cancelOrders(plan, order));
        return plan;
    }
    public static Plan numberPadPressed(Snapshot state, String key) {
        requireExits(state); Models.require(state.splitPartials, "split exits in ViteApp before native partial execution");
        int index = indexForKey(state, key);
        ExitPair pair = state.pairs.get(index);
        CoreTargetExitRules.check(state, pair, state.currentPrice, true);
        Plan plan = new Plan("market_out_partial"); Broker.instantOutOneExitPair(plan, pair); return plan;
    }
    public static Plan keyGPressedWithShift(Snapshot state) {
        requireExits(state); Models.require(state.splitPartials, "split exits in ViteApp before native partial execution");
        Plan plan = new Plan("market_out_half");
        for (var pair : state.pairs.subList(0, (state.pairs.size() + 1) / 2)) {
            CoreTargetExitRules.check(state, pair, state.currentPrice, true);
            Broker.instantOutOneExitPair(plan, pair);
        }
        return plan;
    }
    public static Plan numberKeyPressedAtPrice(Snapshot state, String key, double price) {
        requireExits(state); boolean stop = isStopLeg(state, price);
        Models.require(state.splitPartials || stop, "split exits in ViteApp before native target adjustment");
        Plan plan = new Plan("adjust_exit");
        adjust(state, plan, List.of(state.pairs.get(indexForKey(state, key))), price, stop); return plan;
    }
    public static Plan adjustBatchExitsAtPrice(Snapshot state, String key, double price) {
        requireExits(state); boolean stop = isStopLeg(state, price);
        Models.require(state.splitPartials || stop, "split exits in ViteApp before native target adjustment");
        Plan plan = new Plan("adjust_batch_exits");
        List<ExitPair> pairs = key.equals("KeyT") ? state.pairs : state.pairs.subList(0, (state.pairs.size() + 1) / 2);
        adjust(state, plan, pairs, price, stop); return plan;
    }
    private static void adjust(Snapshot state, Plan plan, List<ExitPair> pairs, double inputPrice, boolean stop) {
        double price = Math.round(inputPrice * 100) / 100.0; // Helper.roundPrice for equities
        // The TS handlers check the proposed price and OrderFlow checks the clamped price again.
        for (var pair : pairs) checkAdjustment(state, pair, price, stop);
        if (stop) price = state.netQuantity > 0 ? Math.min(price, state.bid) : Math.max(price, state.ask);
        for (var pair : pairs) {
            checkAdjustment(state, pair, price, stop);
            Broker.replaceExitPairWithNewPrice(plan, pair, price, stop);
        }
    }
    private static void checkAdjustment(Snapshot state, ExitPair pair, double price, boolean stop) {
        Order leg = stop ? pair.stop : pair.limit;
        Models.require(leg != null && Models.positive(leg.price), "missing exit leg price");
        boolean earlier = stop ? (state.netQuantity > 0 ? price > leg.price : price < leg.price)
                : (state.netQuantity > 0 ? price < leg.price : price > leg.price);
        CoreTargetExitRules.check(state, pair, price, earlier);
    }
    public static Plan flattenPostionKeyPressed(Snapshot state) {
        Models.require(state.netQuantity != 0 && state.rulesSupported, "missing position or unsupported flatten rules");
        for (var pair : state.pairs) Models.require(pair.marketLeg().isBuy == (state.netQuantity < 0),
                "exit side disagrees with position");
        Plan plan = new Plan("flatten");
        double protectedQuantity = state.pairs.stream().filter(pair -> pair.stop != null)
                .mapToDouble(pair -> pair.stop.quantity).sum();
        double uncovered = Math.abs(state.netQuantity) - protectedQuantity;
        if (uncovered > 0) {
            // Preserve Handler's uncovered-shares branch: close ONLY the uncovered shares.
            plan.requests.add(new Request("POST", null, OrderFactory.createSingleOrder(
                    state.symbol, "MARKET", uncovered, 0, state.netQuantity < 0)));
            return plan;
        }
        plan.clearPending = true;
        double remaining = Math.abs(state.netQuantity);
        for (var pair : state.pairs) { Broker.instantOutOneExitPair(plan, pair); remaining -= pair.quantity(); }
        Models.require(remaining >= 0, "exit quantity exceeds position; reconcile first");
        if (remaining > 0) plan.requests.add(new Request("POST", null, OrderFactory.createSingleOrder(
                state.symbol, "MARKET", remaining, 0, state.netQuantity < 0)));
        return plan;
    }
    private static boolean isStopLeg(Snapshot state, double price) {
        return state.netQuantity > 0 ? price < state.currentPrice : price > state.currentPrice;
    }
    private static int indexForKey(Snapshot state, String key) {
        int number = key.equals("KeyM") ? 1 : Integer.parseInt(key.substring(key.length() - 1));
        int index = number == 0 ? 9 : number - 1;
        if (number == 1) index = ExitPairSelection.getFirstSmallestQuantityExitPairIndex(state.pairs);
        Models.require(index >= 0 && index < state.pairs.size(), "exit partial out of range"); return index;
    }
    private static void requireExits(Snapshot state) {
        Models.require(state.netQuantity != 0 && !state.pairs.isEmpty(), "no active exit pairs");
        Models.require(state.rulesSupported, "unsupported exit rules");
        Models.require(Models.positive(state.currentPrice), "missing current price");
        for (var pair : state.pairs) Models.require(pair.marketLeg().isBuy == (state.netQuantity < 0),
                "exit side disagrees with position");
    }
}
