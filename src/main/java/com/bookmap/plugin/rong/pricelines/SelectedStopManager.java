package com.bookmap.plugin.rong.pricelines;

import com.bookmap.plugin.rong.SymbolUtils;
import java.util.Map;
import java.util.HashMap;

/** One temporary stop line; the runtime is authoritative for expiry and consumption. */
public final class SelectedStopManager {
    private final PriceLineStore store;
    private final Map<String, Double> pips = new HashMap<>();
    private String symbol = "";
    private double price;
    private int remainingSeconds;
    public SelectedStopManager(PriceLineStore store) { this.store = store; }
    public synchronized void register(String symbol, double pips) { this.pips.put(SymbolUtils.cleanSymbol(symbol), pips); }
    public synchronized void unregister(String symbol) {
        String clean = SymbolUtils.cleanSymbol(symbol);
        pips.remove(clean);
        if (this.symbol.equals(clean)) update("", 0);
    }
    public synchronized void update(String symbol, double price) {
        update(symbol, price, 30);
    }
    public synchronized void update(String symbol, double price, int remainingSeconds) {
        String clean = SymbolUtils.cleanSymbol(symbol);
        double tick = pips.getOrDefault(clean, 0.0);
        if (!Double.isFinite(price) || price <= 0 || tick <= 0 || remainingSeconds <= 0) { clean = ""; price = 0; remainingSeconds = 0; }
        if (this.symbol.equals(clean) && this.price == price && this.remainingSeconds == remainingSeconds) return;
        if (!this.symbol.isEmpty() && !this.symbol.equals(clean)) store.removeByType(this.symbol, PriceLine.LineType.SELECTED_STOP);
        this.symbol = clean; this.price = price; this.remainingSeconds = remainingSeconds;
        if (!clean.isEmpty()) store.replaceByType(clean, PriceLine.LineType.SELECTED_STOP,
                new PriceLine(clean, PriceLine.LineType.SELECTED_STOP, price / tick, price,
                        String.format(java.util.Locale.ROOT, "Stop %.2f (%d)", price, remainingSeconds)));
    }
}
