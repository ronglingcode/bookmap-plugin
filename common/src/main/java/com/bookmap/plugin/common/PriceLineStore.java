package com.bookmap.plugin.common;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe storage for price lines, keyed by instrument alias.
 */
public class PriceLineStore {

    @FunctionalInterface
    public interface ChangeListener {
        void onLinesChanged(String instrumentAlias);
    }

    private final Map<String, List<PriceLine>> linesByInstrument = new ConcurrentHashMap<>();
    private final List<ChangeListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(ChangeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ChangeListener listener) {
        listeners.remove(listener);
    }

    public void addLine(PriceLine line) {
        linesByInstrument.computeIfAbsent(line.getInstrumentAlias(), k -> new CopyOnWriteArrayList<>())
                .add(line);
        notifyListeners(line.getInstrumentAlias());
    }

    public void removeLine(String instrumentAlias, String lineId) {
        List<PriceLine> lines = linesByInstrument.get(instrumentAlias);
        if (lines != null) {
            lines.removeIf(l -> l.getId().equals(lineId));
            notifyListeners(instrumentAlias);
        }
    }

    /** Remove the most recent line of a given type for the instrument. */
    public void removeLastOfType(String instrumentAlias, PriceLine.LineType type) {
        List<PriceLine> lines = linesByInstrument.get(instrumentAlias);
        if (lines != null) {
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (lines.get(i).getType() == type) {
                    lines.remove(i);
                    notifyListeners(instrumentAlias);
                    return;
                }
            }
        }
    }

    /** Replace the single line of a given type for the instrument, or add if none exists. */
    public void replaceByType(String instrumentAlias, PriceLine.LineType type, PriceLine newLine) {
        List<PriceLine> lines = linesByInstrument.computeIfAbsent(instrumentAlias, k -> new CopyOnWriteArrayList<>());
        lines.removeIf(l -> l.getType() == type);
        lines.add(newLine);
        notifyListeners(instrumentAlias);
    }

    /** Remove all lines of a given type for the instrument. */
    public void removeByType(String instrumentAlias, PriceLine.LineType type) {
        List<PriceLine> lines = linesByInstrument.get(instrumentAlias);
        if (lines != null && lines.removeIf(l -> l.getType() == type)) {
            notifyListeners(instrumentAlias);
        }
    }

    public List<PriceLine> getLines(String instrumentAlias) {
        List<PriceLine> lines = linesByInstrument.get(instrumentAlias);
        return lines != null ? Collections.unmodifiableList(lines) : Collections.emptyList();
    }

    public void clearAll(String instrumentAlias) {
        List<PriceLine> lines = linesByInstrument.remove(instrumentAlias);
        if (lines != null && !lines.isEmpty()) {
            notifyListeners(instrumentAlias);
        }
    }

    public void clearAll() {
        for (String alias : linesByInstrument.keySet()) {
            linesByInstrument.remove(alias);
            notifyListeners(alias);
        }
    }

    private void notifyListeners(String instrumentAlias) {
        for (ChangeListener listener : listeners) {
            listener.onLinesChanged(instrumentAlias);
        }
    }
}
