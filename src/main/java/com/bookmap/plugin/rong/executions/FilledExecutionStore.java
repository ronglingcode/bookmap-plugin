package com.bookmap.plugin.rong.executions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe storage for filled execution markers, keyed by instrument alias.
 */
public class FilledExecutionStore {

    public static final long TRANSIENT_DISPLAY_TTL_NS = 30_000_000_000L;

    @FunctionalInterface
    public interface ChangeListener {
        void onFilledExecutionsChanged(String instrumentAlias);
    }

    private final Map<String, List<FilledExecutionMarker>> markersByInstrument = new ConcurrentHashMap<>();
    private final List<ChangeListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(ChangeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ChangeListener listener) {
        listeners.remove(listener);
    }

    public void replaceAll(String instrumentAlias, Collection<FilledExecutionMarker> markers) {
        if (markers == null || markers.isEmpty()) {
            clearAll(instrumentAlias);
            return;
        }
        markersByInstrument.put(instrumentAlias, new CopyOnWriteArrayList<>(markers));
        notifyListeners(instrumentAlias);
    }

    public List<FilledExecutionMarker> getMarkers(String instrumentAlias) {
        List<FilledExecutionMarker> markers = markersByInstrument.get(instrumentAlias);
        if (markers == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(markers));
    }

    /**
     * Returns every marker in persistent mode, or only executions no more than 30 seconds old.
     */
    public List<FilledExecutionMarker> getMarkersForDisplay(
            String instrumentAlias, boolean persistent, long nowNs) {
        List<FilledExecutionMarker> markers = getMarkers(instrumentAlias);
        if (persistent || markers.isEmpty()) {
            return markers;
        }

        List<FilledExecutionMarker> recent = new ArrayList<>();
        for (FilledExecutionMarker marker : markers) {
            if (nowNs - marker.getTimeNs() <= TRANSIENT_DISPLAY_TTL_NS) {
                recent.add(marker);
            }
        }
        return Collections.unmodifiableList(recent);
    }

    public void clearAll(String instrumentAlias) {
        List<FilledExecutionMarker> markers = markersByInstrument.remove(instrumentAlias);
        if (markers != null && !markers.isEmpty()) {
            notifyListeners(instrumentAlias);
        }
    }

    private void notifyListeners(String instrumentAlias) {
        for (ChangeListener listener : listeners) {
            listener.onFilledExecutionsChanged(instrumentAlias);
        }
    }
}
