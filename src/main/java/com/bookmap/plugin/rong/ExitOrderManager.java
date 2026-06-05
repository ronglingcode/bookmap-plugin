package com.bookmap.plugin.rong;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import com.bookmap.plugin.rong.pricelines.PriceLine;
import com.bookmap.plugin.rong.pricelines.PriceLineStore;

/**
 * Bridges ViteApp exit-order pair updates to rendered Bookmap price lines.
 */
public class ExitOrderManager implements SignalWebSocketServer.ExitOrderPairsConfigListener {

    private static final Color BUY_EXIT_COLOR = new Color(50, 180, 50);
    private static final Color SELL_EXIT_COLOR = new Color(220, 50, 50);

    private final PriceLineStore store;
    private final Map<String, Double> instrumentPips = new ConcurrentHashMap<>();
    private final Map<String, List<ExitOrderPairDefinition>> pairsByInstrument = new ConcurrentHashMap<>();

    public ExitOrderManager(PriceLineStore store) {
        this.store = store;
    }

    public void onInstrumentInitialized(String instrumentAlias, double pips) {
        instrumentPips.put(instrumentAlias, pips);
        redrawInstrument(instrumentAlias);
    }

    public void onInstrumentStopped(String instrumentAlias) {
        instrumentPips.remove(instrumentAlias);
        store.removeByType(instrumentAlias, PriceLine.LineType.EXIT_ORDER);
    }

    @Override
    public void onExitOrderPairsChanged(String symbol, List<ExitOrderPairDefinition> pairs) {
        String instrumentAlias = SymbolUtils.cleanSymbol(symbol);
        pairsByInstrument.put(instrumentAlias, Collections.unmodifiableList(new ArrayList<>(pairs)));
        redrawInstrument(instrumentAlias);
    }

    private void redrawInstrument(String instrumentAlias) {
        Double pips = instrumentPips.get(instrumentAlias);
        if (pips == null) {
            return;
        }

        store.removeByType(instrumentAlias, PriceLine.LineType.EXIT_ORDER);
        List<ExitOrderPairDefinition> pairs =
                pairsByInstrument.getOrDefault(instrumentAlias, Collections.emptyList());

        for (ExitOrderLine line : aggregateLines(buildLineCandidates(pairs))) {
            addLineToStore(instrumentAlias, line, pips);
        }

        PluginLog.info("[ExitOrder] Drew " + pairs.size() + " websocket exit pair(s) for " + instrumentAlias);
    }

    private List<ExitOrderLine> buildLineCandidates(List<ExitOrderPairDefinition> pairs) {
        List<ExitOrderLine> lines = new ArrayList<>();
        for (ExitOrderPairDefinition pair : pairs) {
            addLegLine(lines, pair.getIndex(), "STOP", pair.getStop());
            addLegLine(lines, pair.getIndex(), "LIMIT", pair.getLimit());
        }
        return lines;
    }

    private void addLegLine(List<ExitOrderLine> lines, int index, String legName, ExitOrderLegDefinition leg) {
        if (leg == null || leg.getPrice() <= 0 || !Double.isFinite(leg.getPrice())) {
            return;
        }
        lines.add(new ExitOrderLine(
                leg.getPrice(),
                index + ":" + legName,
                leg.isBuy() ? BUY_EXIT_COLOR : SELL_EXIT_COLOR));
    }

    private List<ExitOrderLine> aggregateLines(List<ExitOrderLine> lines) {
        Map<Double, List<ExitOrderLine>> linesByPrice = new LinkedHashMap<>();
        for (ExitOrderLine line : lines) {
            linesByPrice.computeIfAbsent(line.price, ignored -> new ArrayList<>()).add(line);
        }

        List<ExitOrderLine> result = new ArrayList<>();
        for (List<ExitOrderLine> priceLines : linesByPrice.values()) {
            ExitOrderLine first = priceLines.get(0);
            if (priceLines.size() == 1) {
                result.add(first);
            } else {
                result.add(new ExitOrderLine(first.price, createAggregatedLabel(priceLines), first.color));
            }
        }
        return result;
    }

    private String createAggregatedLabel(List<ExitOrderLine> lines) {
        Map<String, TreeSet<Integer>> indexesByType = new LinkedHashMap<>();
        for (ExitOrderLine line : lines) {
            String[] parts = line.label.split(":", 2);
            if (parts.length != 2) {
                return joinRawLabels(lines);
            }
            try {
                int index = Integer.parseInt(parts[0]);
                indexesByType.computeIfAbsent(parts[1], ignored -> new TreeSet<>()).add(index);
            } catch (NumberFormatException e) {
                return joinRawLabels(lines);
            }
        }

        List<String> labelParts = new ArrayList<>();
        for (Map.Entry<String, TreeSet<Integer>> entry : indexesByType.entrySet()) {
            labelParts.add(formatNumberRanges(entry.getValue()) + ":" + entry.getKey());
        }
        return String.join(",", labelParts);
    }

    private String joinRawLabels(List<ExitOrderLine> lines) {
        List<String> labels = new ArrayList<>();
        for (ExitOrderLine line : lines) {
            labels.add(line.label);
        }
        return String.join(",", labels);
    }

    private String formatNumberRanges(TreeSet<Integer> indexes) {
        if (indexes.isEmpty()) {
            return "";
        }
        List<String> ranges = new ArrayList<>();
        Integer start = null;
        Integer previous = null;
        for (Integer index : indexes) {
            if (start == null) {
                start = index;
                previous = index;
                continue;
            }
            if (index == previous + 1) {
                previous = index;
            } else {
                ranges.add(formatRange(start, previous));
                start = index;
                previous = index;
            }
        }
        ranges.add(formatRange(start, previous));
        return String.join(",", ranges);
    }

    private String formatRange(int start, int end) {
        return start == end ? Integer.toString(start) : start + "-" + end;
    }

    private void addLineToStore(String instrumentAlias, ExitOrderLine line, double pips) {
        double priceInTicks = line.price / pips;
        store.addLine(new PriceLine(
                instrumentAlias,
                PriceLine.LineType.EXIT_ORDER,
                priceInTicks,
                line.price,
                line.label,
                line.color));
    }

    public void shutdown() {
        instrumentPips.clear();
        pairsByInstrument.clear();
    }

    private static class ExitOrderLine {
        private final double price;
        private final String label;
        private final Color color;

        private ExitOrderLine(double price, String label, Color color) {
            this.price = price;
            this.label = label;
            this.color = color;
        }
    }
}
