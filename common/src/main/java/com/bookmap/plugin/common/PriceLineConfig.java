package com.bookmap.plugin.common;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configurable key-to-line-type bindings.
 * Keys are stored as lowercase strings (e.g. "s", "t", "e").
 */
public class PriceLineConfig {

    private final Map<String, PriceLine.LineType> keyToLineType = new ConcurrentHashMap<>();

    public PriceLineConfig() {
        // Load defaults
        for (PriceLine.LineType type : PriceLine.LineType.values()) {
            if (type.defaultKey != null) {
                keyToLineType.put(type.defaultKey, type);
            }
        }
    }

    /** Get the line type for a key press, or null if not bound. */
    public PriceLine.LineType getLineType(String keyCode) {
        if (keyCode == null) return null;
        return keyToLineType.get(keyCode.toLowerCase());
    }

    /** Set a key binding. Removes any previous binding for that key. */
    public void setBinding(String key, PriceLine.LineType type) {
        // Remove any existing binding for this type
        keyToLineType.entrySet().removeIf(e -> e.getValue() == type);
        if (key != null && !key.isEmpty()) {
            keyToLineType.put(key.toLowerCase(), type);
        }
    }

    /** Get the current key for a line type, or null if unbound. */
    public String getKeyForType(PriceLine.LineType type) {
        for (Map.Entry<String, PriceLine.LineType> entry : keyToLineType.entrySet()) {
            if (entry.getValue() == type) {
                return entry.getKey();
            }
        }
        return null;
    }

    public Map<String, PriceLine.LineType> getBindings() {
        return Collections.unmodifiableMap(keyToLineType);
    }
}
