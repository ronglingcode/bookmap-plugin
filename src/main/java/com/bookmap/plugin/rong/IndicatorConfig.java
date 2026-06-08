package com.bookmap.plugin.rong;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configuration for automatic indicators (enable/disable toggles).
 * Each indicator is identified by a string key.
 */
public class IndicatorConfig {

    public static final String PREMARKET_HIGH_LOW = "premarket_high_low";
    public static final String CAM_PIVOTS = "cam_pivots";
    public static final String ORDER_WALL_SIZE_LABELS = "order_wall_size_labels";
    public static final String ORDER_WALL_CHANGE_ALERTS = "order_wall_change_alerts";
    public static final String ORDER_WALL_CHANGE_SOUND = "order_wall_change_sound";

    private final Map<String, Boolean> enabled = new ConcurrentHashMap<>();

    /** Listeners notified when any indicator toggle changes. */
    @FunctionalInterface
    public interface ChangeListener {
        void onIndicatorConfigChanged(String indicatorKey, boolean enabled);
    }

    private final java.util.List<ChangeListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public IndicatorConfig() {
        // Defaults: all indicators enabled
        enabled.put(PREMARKET_HIGH_LOW, true);
        enabled.put(CAM_PIVOTS, true);
        enabled.put(ORDER_WALL_SIZE_LABELS, true);
        enabled.put(ORDER_WALL_CHANGE_ALERTS, true);
        enabled.put(ORDER_WALL_CHANGE_SOUND, true);
    }

    public boolean isEnabled(String indicatorKey) {
        return enabled.getOrDefault(indicatorKey, false);
    }

    public void setEnabled(String indicatorKey, boolean value) {
        enabled.put(indicatorKey, value);
        for (ChangeListener l : listeners) {
            l.onIndicatorConfigChanged(indicatorKey, value);
        }
    }

    public void addChangeListener(ChangeListener listener) {
        listeners.add(listener);
    }

    public void removeChangeListener(ChangeListener listener) {
        listeners.remove(listener);
    }
}
