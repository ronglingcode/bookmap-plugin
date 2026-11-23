package com.bookmap.plugin.rong.tradebuttons;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Tradebook config pushed from the trading app into the Bookmap trade panel.
 */
public class TradebookButtonGroup {

    private final String id;
    private final String label;
    private final boolean sideIsLong;
    private final String tradebookId;
    private final String tradebookName;
    private final List<String> entryMethods;

    public TradebookButtonGroup(
            String id,
            String label,
            boolean sideIsLong,
            String tradebookId,
            String tradebookName,
            List<String> entryMethods) {
        this.id = normalize(id);
        this.label = normalize(label);
        this.sideIsLong = sideIsLong;
        this.tradebookId = normalize(tradebookId);
        this.tradebookName = normalize(tradebookName);
        this.entryMethods = Collections.unmodifiableList(new ArrayList<>(entryMethods));
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public boolean isLong() {
        return sideIsLong;
    }

    public String getTradebookId() {
        return tradebookId;
    }

    public String getTradebookName() {
        return tradebookName;
    }

    public List<String> getEntryMethods() {
        return entryMethods;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TradebookButtonGroup)) return false;
        TradebookButtonGroup group = (TradebookButtonGroup) other;
        return sideIsLong == group.sideIsLong && id.equals(group.id) && label.equals(group.label)
                && tradebookId.equals(group.tradebookId) && tradebookName.equals(group.tradebookName)
                && entryMethods.equals(group.entryMethods);
    }

    @Override public int hashCode() {
        return Objects.hash(id, label, sideIsLong, tradebookId, tradebookName, entryMethods);
    }

    private String normalize(String value) {
        return value == null ? "" : value;
    }
}
