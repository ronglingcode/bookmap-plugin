package com.bookmap.plugin.rong.tradebuttons;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tradebook config pushed from the trading app into the Bookmap trade panel.
 */
public class TradebookButtonGroup {

    private final String id;
    private final String label;
    private final String side;
    private final String tradebookId;
    private final String tradebookName;
    private final List<String> entryMethods;

    public TradebookButtonGroup(
            String id,
            String label,
            String side,
            String tradebookId,
            String tradebookName,
            List<String> entryMethods) {
        this.id = normalize(id);
        this.label = normalize(label);
        this.side = normalize(side);
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

    public String getSide() {
        return side;
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

    private String normalize(String value) {
        return value == null ? "" : value;
    }
}
