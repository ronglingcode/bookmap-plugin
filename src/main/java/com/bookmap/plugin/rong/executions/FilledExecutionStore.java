package com.bookmap.plugin.rong.executions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe storage for filled execution markers, keyed by instrument alias.
 */
public class FilledExecutionStore {

    public static final long TRANSIENT_DISPLAY_TTL_NS = 30_000_000_000L;
    public static final long GROUP_WINDOW_NS = 2_000_000_000L;

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
     * Groups same-side fills within two seconds of the first fill. Groups use total
     * quantity, volume-weighted price, and the latest fill time. Transient groups
     * remain visible for 30 seconds after their latest fill.
     */
    public List<FilledExecutionMarker> getMarkersForDisplay(
            String instrumentAlias, boolean persistent, long nowNs) {
        List<FilledExecutionMarker> markers = groupMarkers(getMarkers(instrumentAlias));
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

    private List<FilledExecutionMarker> groupMarkers(List<FilledExecutionMarker> markers) {
        List<FilledExecutionMarker> sorted = new ArrayList<>(markers);
        sorted.sort(Comparator.comparingLong(FilledExecutionMarker::getTimeNs));
        List<FilledExecutionMarker> grouped = new ArrayList<>();
        // Independent windows allow interleaved buys and sells to group by side.
        for (boolean buy : new boolean[] {true, false}) {
            FilledExecutionMarker aggregate = null;
            long firstTimeNs = 0;
            for (FilledExecutionMarker marker : sorted) {
                if (marker.isBuy() != buy) {
                    continue;
                }
                if (aggregate == null || marker.getTimeNs() - firstTimeNs > GROUP_WINDOW_NS) {
                    if (aggregate != null) {
                        grouped.add(aggregate);
                    }
                    aggregate = marker;
                    firstTimeNs = marker.getTimeNs();
                } else {
                    double quantity = aggregate.getQuantity() + marker.getQuantity();
                    double weight = marker.getQuantity() / quantity;
                    aggregate = new FilledExecutionMarker(
                            marker.getInstrumentAlias(),
                            aggregate.getPriceInTicks()
                                    + (marker.getPriceInTicks() - aggregate.getPriceInTicks()) * weight,
                            aggregate.getRealPrice()
                                    + (marker.getRealPrice() - aggregate.getRealPrice()) * weight,
                            quantity, buy,
                            aggregate.isOpening() || marker.isOpening(),
                            marker.getTimeNs());
                }
            }
            if (aggregate != null) {
                grouped.add(aggregate);
            }
        }
        grouped.sort(Comparator.comparingLong(FilledExecutionMarker::getTimeNs));
        return Collections.unmodifiableList(grouped);
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
