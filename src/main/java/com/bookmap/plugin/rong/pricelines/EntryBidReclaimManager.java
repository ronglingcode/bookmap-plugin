package com.bookmap.plugin.rong.pricelines;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import com.bookmap.plugin.rong.SymbolUtils;

/** One advisory entry-bid line per short position, independent of pattern monitors. */
public final class EntryBidReclaimManager {
    private final PriceLineStore store;
    private final Map<String, State> states = new HashMap<>();

    private static final class State {
        double pips, entryBid, averagePrice, latestBid;
        boolean shortPosition, reclaimed;
    }

    public EntryBidReclaimManager(PriceLineStore store) { this.store = store; }

    public synchronized void register(String symbol, double pips) {
        String clean = SymbolUtils.cleanSymbol(symbol);
        states.computeIfAbsent(clean, ignored -> new State()).pips = pips;
        redraw(clean);
    }

    public synchronized void unregister(String symbol) {
        String clean = SymbolUtils.cleanSymbol(symbol);
        states.remove(clean);
        store.removeByType(clean, PriceLine.LineType.ENTRY_BID_RECLAIM);
    }

    public synchronized void update(String symbol, boolean shortPosition, double entryBid, double averagePrice) {
        String clean = SymbolUtils.cleanSymbol(symbol);
        State state = states.computeIfAbsent(clean, ignored -> new State());
        if (state.shortPosition == shortPosition && state.entryBid == entryBid && state.averagePrice == averagePrice) return;
        if (!shortPosition || state.entryBid != entryBid) state.reclaimed = false;
        state.shortPosition = shortPosition;
        state.entryBid = entryBid;
        state.averagePrice = averagePrice;
        if (shortPosition && positive(entryBid) && positive(state.latestBid) && state.latestBid >= entryBid)
            state.reclaimed = true;
        redraw(clean);
    }

    public synchronized void onBid(String symbol, double bid) {
        String clean = SymbolUtils.cleanSymbol(symbol);
        State state = states.get(clean);
        if (state != null) state.latestBid = bid;
        if (state == null || !state.shortPosition || state.reclaimed || !positive(state.entryBid)
                || !positive(bid) || bid < state.entryBid) return;
        state.reclaimed = true;
        redraw(clean);
    }

    private void redraw(String symbol) {
        State state = states.get(symbol);
        double price = positive(state.entryBid) ? state.entryBid : state.averagePrice;
        if (!state.shortPosition || !positive(state.pips) || !positive(price)) {
            store.removeByType(symbol, PriceLine.LineType.ENTRY_BID_RECLAIM);
            return;
        }
        String label = !positive(state.entryBid)
                ? "SET ENTRY BID: hover + I before target #4+ (reminder at avg entry)"
                : state.reclaimed ? "entry bid to reclaim - RECLAIMED: STOP OUT"
                : "entry bid to reclaim - wait for L1 bid reclaim";
        Color color = state.reclaimed ? new Color(255, 55, 65) : new Color(255, 220, 0);
        store.replaceByType(symbol, PriceLine.LineType.ENTRY_BID_RECLAIM,
                new PriceLine(symbol, PriceLine.LineType.ENTRY_BID_RECLAIM, price / state.pips, price, label, color));
    }

    private static boolean positive(double value) { return Double.isFinite(value) && value > 0; }
}
