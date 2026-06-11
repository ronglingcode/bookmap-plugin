package com.bookmap.plugin.rong;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.bookmap.plugin.rong.pricelines.PriceLine;
import com.bookmap.plugin.rong.pricelines.PriceLineStore;

/**
 * Draws pending entry orders from ViteApp account snapshots on Bookmap.
 */
public class PendingEntryOrderManager implements SignalWebSocketServer.AccountStateListener {

    private static final Color BUY_ENTRY_COLOR = new Color(40, 190, 90);
    private static final Color SELL_ENTRY_COLOR = new Color(230, 80, 80);

    private final PriceLineStore store;
    private final Map<String, Double> instrumentPips = new ConcurrentHashMap<>();
    private final Map<String, AccountStateDefinition> statesByInstrument = new ConcurrentHashMap<>();

    public PendingEntryOrderManager(PriceLineStore store) {
        this.store = store;
    }

    public void onInstrumentInitialized(String instrumentAlias, double pips) {
        String cleanInstrumentAlias = SymbolUtils.cleanSymbol(instrumentAlias);
        instrumentPips.put(cleanInstrumentAlias, pips);
        redrawInstrument(cleanInstrumentAlias);
    }

    public void onInstrumentStopped(String instrumentAlias) {
        String cleanInstrumentAlias = SymbolUtils.cleanSymbol(instrumentAlias);
        instrumentPips.remove(cleanInstrumentAlias);
        store.removeByType(cleanInstrumentAlias, PriceLine.LineType.ENTRY_ORDER);
    }

    @Override
    public void onAccountStateChanged(AccountStateDefinition state) {
        if (state == null || state.getSymbol().isEmpty()) {
            return;
        }
        String instrumentAlias = SymbolUtils.cleanSymbol(state.getSymbol());
        statesByInstrument.put(instrumentAlias, state);
        redrawInstrument(instrumentAlias);
    }

    private void redrawInstrument(String instrumentAlias) {
        Double pips = instrumentPips.get(instrumentAlias);
        if (pips == null) {
            return;
        }

        store.removeByType(instrumentAlias, PriceLine.LineType.ENTRY_ORDER);
        AccountStateDefinition state = statesByInstrument.get(instrumentAlias);
        List<EntryOrderLine> lines = state == null
                ? Collections.emptyList()
                : aggregateLines(buildLineCandidates(state));

        for (EntryOrderLine line : lines) {
            addLineToStore(instrumentAlias, line, pips);
        }

        PluginLog.info("[EntryOrder] Drew " + lines.size() + " pending entry line(s) for " + instrumentAlias);
    }

    private List<EntryOrderLine> buildLineCandidates(AccountStateDefinition state) {
        List<EntryOrderLine> lines = new ArrayList<>();
        for (AccountOrderDefinition order : state.getOpenOrders()) {
            if (!isPendingEntry(order) || order.getPrice() <= 0 || !Double.isFinite(order.getPrice())) {
                continue;
            }
            lines.add(new EntryOrderLine(
                    order.getPrice(),
                    order.isBuy() ? "BUY ENTRY" : "SELL ENTRY",
                    order.isBuy() ? BUY_ENTRY_COLOR : SELL_ENTRY_COLOR));
        }
        return lines;
    }

    private boolean isPendingEntry(AccountOrderDefinition order) {
        return order != null && "ENTRY".equalsIgnoreCase(order.getRole());
    }

    private List<EntryOrderLine> aggregateLines(List<EntryOrderLine> lines) {
        Map<Double, List<EntryOrderLine>> linesByPrice = new LinkedHashMap<>();
        for (EntryOrderLine line : lines) {
            linesByPrice.computeIfAbsent(line.price, ignored -> new ArrayList<>()).add(line);
        }

        List<EntryOrderLine> result = new ArrayList<>();
        for (List<EntryOrderLine> priceLines : linesByPrice.values()) {
            EntryOrderLine first = priceLines.get(0);
            if (priceLines.size() == 1) {
                result.add(first);
            } else {
                result.add(new EntryOrderLine(first.price, createAggregatedLabel(priceLines), first.color));
            }
        }
        return result;
    }

    private String createAggregatedLabel(List<EntryOrderLine> lines) {
        Map<String, Integer> countByLabel = new LinkedHashMap<>();
        for (EntryOrderLine line : lines) {
            countByLabel.put(line.label, countByLabel.getOrDefault(line.label, 0) + 1);
        }

        List<String> labels = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : countByLabel.entrySet()) {
            if (entry.getValue() == 1) {
                labels.add(entry.getKey());
            } else {
                labels.add(entry.getKey() + " x" + entry.getValue());
            }
        }
        return String.join(",", labels);
    }

    private void addLineToStore(String instrumentAlias, EntryOrderLine line, double pips) {
        double priceInTicks = line.price / pips;
        store.addLine(new PriceLine(
                instrumentAlias,
                PriceLine.LineType.ENTRY_ORDER,
                priceInTicks,
                line.price,
                line.label,
                line.color));
    }

    public void shutdown() {
        instrumentPips.clear();
        statesByInstrument.clear();
    }

    private static class EntryOrderLine {
        private final double price;
        private final String label;
        private final Color color;

        private EntryOrderLine(double price, String label, Color color) {
            this.price = price;
            this.label = label;
            this.color = color;
        }
    }
}
