package com.bookmap.plugin.rong.tradebuttons;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.bookmap.plugin.rong.miniviteapp.core.algorithms.EntrySizing;

/** Shared risk preference with per-symbol fixed-share selections for chart B/S entries. */
public final class HotkeyRiskSelection {
    public static final List<String> ENTRY_METHODS = List.of("1 R", "0.5 R", "0.1 R");
    private final PropertyChangeSupport changes = new PropertyChangeSupport(this);
    private volatile String entryMethod = "1 R";
    private final Map<String, List<String>> shareMethods = new ConcurrentHashMap<>();
    private final Map<String, String> shareSelections = new ConcurrentHashMap<>();

    public List<String> getEntryMethods(String symbol) {
        List<String> methods = new ArrayList<>(ENTRY_METHODS);
        methods.addAll(shareMethods.getOrDefault(symbol, List.of()));
        return methods;
    }

    public String getEntryMethod(String symbol) {
        return shareSelections.getOrDefault(symbol, entryMethod);
    }

    public void setFixedShareMethods(String symbol, List<String> methods) {
        List<String> shares = new ArrayList<>();
        for (String method : methods) if (EntrySizing.fixedShares(method) > 0 && !shares.contains(method)) shares.add(method);
        List<String> previous = shareMethods.put(symbol, List.copyOf(shares));
        String selected = shareSelections.get(symbol);
        if (selected != null && !shares.contains(selected)) shareSelections.remove(symbol, selected);
        if (!shares.equals(previous)) changes.firePropertyChange("entryMethods", null, symbol);
    }

    public void setEntryMethod(String symbol, String method) {
        if (ENTRY_METHODS.contains(method)) { setEntryMethod(method); return; }
        if (!shareMethods.getOrDefault(symbol, List.of()).contains(method)) {
            throw new IllegalArgumentException("Unsupported hotkey size: " + method);
        }
        String previous = getEntryMethod(symbol);
        shareSelections.put(symbol, method);
        changes.firePropertyChange("entryMethod", previous, method);
    }

    public String getEntryMethod() {
        return entryMethod;
    }

    public void setEntryMethod(String method) {
        if (!ENTRY_METHODS.contains(method)) {
            throw new IllegalArgumentException("Unsupported hotkey risk: " + method);
        }
        String previous = entryMethod;
        entryMethod = method;
        boolean hadShareSelections = !shareSelections.isEmpty();
        shareSelections.clear();
        changes.firePropertyChange("entryMethod", hadShareSelections ? null : previous, method);
    }

    public void addListener(PropertyChangeListener listener) {
        changes.addPropertyChangeListener(listener);
    }

    public void removeListener(PropertyChangeListener listener) {
        changes.removePropertyChangeListener(listener);
    }
}
