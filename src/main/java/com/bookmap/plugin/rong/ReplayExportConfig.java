package com.bookmap.plugin.rong;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Configuration for optional replay export from the live Rong plugin.
 */
public class ReplayExportConfig {

    private boolean enabled = Boolean.parseBoolean(
            System.getProperty("rong.replayExport.enabled", "false"));
    private final List<ChangeListener> listeners = new CopyOnWriteArrayList<>();

    @FunctionalInterface
    public interface ChangeListener {
        void onReplayExportConfigChanged(boolean enabled);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        for (ChangeListener listener : listeners) {
            listener.onReplayExportConfigChanged(enabled);
        }
    }

    public void addChangeListener(ChangeListener listener) {
        listeners.add(listener);
    }

    public void removeChangeListener(ChangeListener listener) {
        listeners.remove(listener);
    }
}
