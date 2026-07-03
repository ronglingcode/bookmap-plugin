package com.bookmap.plugin.rong.pricelines;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe storage for price zones, keyed by instrument alias.
 */
public class PriceZoneStore {

    @FunctionalInterface
    public interface ChangeListener {
        void onZonesChanged(String instrumentAlias);
    }

    private final Map<String, List<PriceZone>> zonesByInstrument = new ConcurrentHashMap<>();
    private final List<ChangeListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(ChangeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ChangeListener listener) {
        listeners.remove(listener);
    }

    public void addZone(PriceZone zone) {
        zonesByInstrument.computeIfAbsent(zone.getInstrumentAlias(), ignored -> new CopyOnWriteArrayList<>())
                .add(zone);
        notifyListeners(zone.getInstrumentAlias());
    }

    public void replaceAll(String instrumentAlias, List<PriceZone> zones) {
        if (zones == null || zones.isEmpty()) {
            clearAll(instrumentAlias);
            return;
        }
        zonesByInstrument.put(instrumentAlias, new CopyOnWriteArrayList<>(zones));
        notifyListeners(instrumentAlias);
    }

    public List<PriceZone> getZones(String instrumentAlias) {
        List<PriceZone> zones = zonesByInstrument.get(instrumentAlias);
        return zones != null ? Collections.unmodifiableList(zones) : Collections.emptyList();
    }

    public void clearAll(String instrumentAlias) {
        List<PriceZone> zones = zonesByInstrument.remove(instrumentAlias);
        if (zones != null && !zones.isEmpty()) {
            notifyListeners(instrumentAlias);
        }
    }

    private void notifyListeners(String instrumentAlias) {
        for (ChangeListener listener : listeners) {
            listener.onZonesChanged(instrumentAlias);
        }
    }
}
