package com.bookmap.plugin.rong.tradebuttons;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.List;

/** Session-wide requested risk for chart B/S entries, separate from entry-button sizes. */
public final class HotkeyRiskSelection {
    public static final List<String> ENTRY_METHODS = List.of("1 R", "0.5 R", "0.1 R");
    private final PropertyChangeSupport changes = new PropertyChangeSupport(this);
    private volatile String entryMethod = "1 R";

    public String getEntryMethod() {
        return entryMethod;
    }

    public void setEntryMethod(String method) {
        if (!ENTRY_METHODS.contains(method)) {
            throw new IllegalArgumentException("Unsupported hotkey risk: " + method);
        }
        String previous = entryMethod;
        entryMethod = method;
        changes.firePropertyChange("entryMethod", previous, method);
    }

    public void addListener(PropertyChangeListener listener) {
        changes.addPropertyChangeListener(listener);
    }

    public void removeListener(PropertyChangeListener listener) {
        changes.removePropertyChangeListener(listener);
    }
}
