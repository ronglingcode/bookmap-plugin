package com.bookmap.plugin.rong.pricelines;

import com.bookmap.plugin.rong.SymbolUtils;
import com.google.gson.JsonObject;
import java.util.*;
import static com.bookmap.plugin.rong.miniviteapp.models.DomainJson.*;

/** Persistent per-symbol local stop lines; never masquerades as a broker order. */
public final class PremarketStopLineManager {
    private final PriceLineStore store;
    private final Map<String, Double> ticks = new HashMap<>();
    private final Map<String, String> displayed = new HashMap<>();
    public PremarketStopLineManager(PriceLineStore store) { this.store = store; }
    public synchronized void register(String symbol, double tick) { ticks.put(SymbolUtils.cleanSymbol(symbol), tick); }
    public synchronized void unregister(String symbol) {
        String clean = SymbolUtils.cleanSymbol(symbol); ticks.remove(clean); displayed.remove(clean);
        store.removeByType(clean, PriceLine.LineType.PREMARKET_STOP);
    }
    public synchronized void update(String symbol, JsonObject stop) {
        String clean = SymbolUtils.cleanSymbol(symbol), snapshot = stop.toString();
        if (snapshot.equals(displayed.get(clean))) return;
        displayed.put(clean, snapshot); double price = number(stop, "price"), tick = ticks.getOrDefault(clean, 0.0);
        if (price <= 0 || tick <= 0) { store.removeByType(clean, PriceLine.LineType.PREMARKET_STOP); return; }
        String label = String.format(Locale.ROOT, "PM LOCAL STOP %.4f | %s", price, string(stop, "status"));
        store.replaceByType(clean, PriceLine.LineType.PREMARKET_STOP,
                new PriceLine(clean, PriceLine.LineType.PREMARKET_STOP, price / tick, price, label));
    }
}
