package com.bookmap.plugin.rong;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configuration for automatic indicators (enable/disable toggles).
 * Each indicator is identified by a string key.
 */
public class IndicatorConfig {

    public static final String CAM_PIVOTS = "cam_pivots";
    public static final String ORDER_WALL_SIZE_LABELS = "order_wall_size_labels";
    public static final String ORDER_WALL_CHANGE_ALERTS = "order_wall_change_alerts";
    public static final String ORDER_WALL_CHANGE_SOUND = "order_wall_change_sound";
    public static final String FIRE_KEYBOARD_EVENT = "fire_keyboard_event";
    /**
     * Controls filled-execution label retention: enabled is persistent, disabled is a 30-second display.
     * The stored key is retained for compatibility with the previous enable/disable setting.
     */
    public static final String FILLED_EXECUTION_MARKERS = "filled_execution_markers";
    public static final String SIGNAL_COMPOSER = "signal_composer";
    public static final String VWAP = "vwap";
    public static final String TRADING_NOTIFICATION_SOUND = "trading_notification_sound";

    private final Map<String, Boolean> enabled = new ConcurrentHashMap<>();

    /** Listeners notified when any indicator toggle changes. */
    @FunctionalInterface
    public interface ChangeListener {
        void onIndicatorConfigChanged(String indicatorKey, boolean enabled);
    }

    private final java.util.List<ChangeListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public IndicatorConfig() {
        // Material, in-range wall-change alerts are filtered enough to be useful by default.
        enabled.put(CAM_PIVOTS, false);
        enabled.put(ORDER_WALL_SIZE_LABELS, true);
        enabled.put(ORDER_WALL_CHANGE_ALERTS, true);
        enabled.put(ORDER_WALL_CHANGE_SOUND, true);
        enabled.put(FIRE_KEYBOARD_EVENT, true);
        enabled.put(FILLED_EXECUTION_MARKERS, true);
        enabled.put(SIGNAL_COMPOSER, true);
        enabled.put(VWAP, true);
        enabled.put(TRADING_NOTIFICATION_SOUND, true);
    }

    public boolean isEnabled(String indicatorKey) {
        return enabled.getOrDefault(indicatorKey, false);
    }

    /** Global master switch for order-change monitoring outputs. */
    public boolean areOrderChangeAlertsEnabled() {
        return isEnabled(ORDER_WALL_CHANGE_ALERTS);
    }

    /** The sound preference only takes effect while the order-change feature is enabled. */
    public boolean isOrderChangeSoundEnabled() {
        return areOrderChangeAlertsEnabled() && isEnabled(ORDER_WALL_CHANGE_SOUND);
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
