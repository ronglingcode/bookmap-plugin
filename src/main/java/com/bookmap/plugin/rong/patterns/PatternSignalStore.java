package com.bookmap.plugin.rong.patterns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class PatternSignalStore {

    public static final long DISPLAY_TTL_MS = 30_000;
    private static final int MAX_SIGNALS_PER_INSTRUMENT = 20;

    @FunctionalInterface
    public interface ChangeListener {
        void onPatternSignalsChanged(String instrumentAlias);
    }

    private final Map<String, CopyOnWriteArrayList<BookmapPatternSignal>> signalsByInstrument =
            new ConcurrentHashMap<>();
    private final List<ChangeListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(ChangeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ChangeListener listener) {
        listeners.remove(listener);
    }

    public void addOrUpdate(BookmapPatternSignal signal) {
        CopyOnWriteArrayList<BookmapPatternSignal> signals = signalsByInstrument.computeIfAbsent(
                signal.getInstrumentAlias(), ignored -> new CopyOnWriteArrayList<>());
        signals.removeIf(existing -> existing.getEpisodeKey().equals(signal.getEpisodeKey()));
        signals.add(0, signal);
        while (signals.size() > MAX_SIGNALS_PER_INSTRUMENT) {
            signals.remove(signals.size() - 1);
        }
        notifyListeners(signal.getInstrumentAlias());
    }

    public List<BookmapPatternSignal> getRecentSignals(String instrumentAlias, long nowMs) {
        CopyOnWriteArrayList<BookmapPatternSignal> signals = signalsByInstrument.get(instrumentAlias);
        if (signals == null) {
            return Collections.emptyList();
        }
        List<BookmapPatternSignal> recent = new ArrayList<>();
        for (BookmapPatternSignal signal : signals) {
            if (nowMs - signal.getCreatedAtMs() <= DISPLAY_TTL_MS) {
                recent.add(signal);
            }
        }
        return recent;
    }

    public boolean hasRecentSignals(long nowMs) {
        for (String instrumentAlias : signalsByInstrument.keySet()) {
            if (!getRecentSignals(instrumentAlias, nowMs).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public void clearAll(String instrumentAlias) {
        if (signalsByInstrument.remove(instrumentAlias) != null) {
            notifyListeners(instrumentAlias);
        }
    }

    private void notifyListeners(String instrumentAlias) {
        for (ChangeListener listener : listeners) {
            listener.onPatternSignalsChanged(instrumentAlias);
        }
    }
}
