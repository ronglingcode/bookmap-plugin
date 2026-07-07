package com.bookmap.plugin.rong;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared configuration for the absolute wall threshold floor.
 */
public class WallThresholdConfig {

    public static final int DEFAULT_THRESHOLD_FLOOR = 5_000;
    public static final int MAX_THRESHOLD_FLOOR = 10_000_000;

    private volatile int thresholdFloor = DEFAULT_THRESHOLD_FLOOR;
    private final List<ChangeListener> listeners = new CopyOnWriteArrayList<>();

    @FunctionalInterface
    public interface ChangeListener {
        void onWallThresholdFloorChanged(int thresholdFloor);
    }

    public int getThresholdFloor() {
        return thresholdFloor;
    }

    public void setThresholdFloor(int thresholdFloor) {
        int normalizedThresholdFloor = Math.max(0, Math.min(MAX_THRESHOLD_FLOOR, thresholdFloor));
        if (this.thresholdFloor == normalizedThresholdFloor) {
            return;
        }
        this.thresholdFloor = normalizedThresholdFloor;
        for (ChangeListener listener : listeners) {
            listener.onWallThresholdFloorChanged(normalizedThresholdFloor);
        }
    }

    public void addChangeListener(ChangeListener listener) {
        listeners.add(listener);
    }

    public void removeChangeListener(ChangeListener listener) {
        listeners.remove(listener);
    }
}
