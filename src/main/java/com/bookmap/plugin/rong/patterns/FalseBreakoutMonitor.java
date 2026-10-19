package com.bookmap.plugin.rong.patterns;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntSupplier;

/** The shared bid engine, armed by executed buying through a qualifying offer. */
public final class FalseBreakoutMonitor extends BidBreakdownMonitor {
    public static final String PATTERN = "falseBreakout";
    private final IntSupplier offerThreshold;
    private final TreeMap<Integer, Integer> offers = new TreeMap<>();
    private boolean breakout;
    private Integer currentPrice, armedOffer;
    private int offerSize, lastThreshold;
    private long executed;

    public FalseBreakoutMonitor() { this(() -> 5000); }
    public FalseBreakoutMonitor(IntSupplier threshold) { super(() -> true); offerThreshold = threshold; }
    @Override protected boolean startConditionMet() { selectOffer(); return breakout; }
    @Override protected boolean waitingForOffer() { return !breakout; }
    @Override protected Integer offerTick() { return armedOffer; }
    @Override protected void resetStart(boolean clearBook) {
        breakout = false; currentPrice = armedOffer = null; offerSize = 0; executed = 0;
        if (clearBook) offers.clear();
    }
    @Override protected void observeDepth(boolean bid, int price, int size) {
        if (bid || price <= 0) return;
        selectOffer(); // Retain an armed offer through depth-before-execution callback ordering.
        if (size > 0) offers.put(price, size); else offers.remove(price);
        selectOffer();
    }
    private void selectOffer() {
        int threshold = offerThreshold.getAsInt();
        if (threshold != lastThreshold) {
            if (armedOffer != null && offers.getOrDefault(armedOffer, 0) <= threshold) { armedOffer = null; executed = 0; }
            lastThreshold = threshold;
        }
        if (breakout || currentPrice == null) return;
        for (Map.Entry<Integer, Integer> offer : offers.tailMap(currentPrice, false).entrySet()) {
            if (offer.getValue() <= threshold) continue;
            if (armedOffer == null || armedOffer > currentPrice && offers.getOrDefault(armedOffer, 0) > threshold) {
                if (!offer.getKey().equals(armedOffer)) { executed = 0; offerSize = offer.getValue(); }
                armedOffer = offer.getKey();
                if (executed == 0) offerSize = offer.getValue();
            }
            break;
        }
    }
    @Override protected void observeTrade(int price, int size, Boolean buyAggressor, boolean canObserve) {
        selectOffer();
        if (canObserve && !breakout && armedOffer != null && !Boolean.FALSE.equals(buyAggressor)) {
            if (price == armedOffer) executed += size;
            if (executed >= offerSize || price > armedOffer && executed > 0) { breakout = true; armedOffer = null; }
        }
        currentPrice = price;
        if (armedOffer != null && offers.getOrDefault(armedOffer, 0) <= offerThreshold.getAsInt() && executed == 0) armedOffer = null;
        selectOffer();
    }
}
