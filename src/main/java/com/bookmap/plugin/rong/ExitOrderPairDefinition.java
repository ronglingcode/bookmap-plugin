package com.bookmap.plugin.rong;

/**
 * One numbered exit-order pair pushed from ViteApp to Bookmap.
 */
public class ExitOrderPairDefinition {

    private final String instrument;
    private final int index;
    private final String source;
    private final String parentOrderId;
    private final ExitOrderLegDefinition stop;
    private final ExitOrderLegDefinition limit;

    public ExitOrderPairDefinition(
            String instrument,
            int index,
            String source,
            String parentOrderId,
            ExitOrderLegDefinition stop,
            ExitOrderLegDefinition limit) {
        this.instrument = normalize(instrument);
        this.index = index;
        this.source = normalize(source);
        this.parentOrderId = normalize(parentOrderId);
        this.stop = stop;
        this.limit = limit;
    }

    public String getInstrument() {
        return instrument;
    }

    public int getIndex() {
        return index;
    }

    public String getSource() {
        return source;
    }

    public String getParentOrderId() {
        return parentOrderId;
    }

    public ExitOrderLegDefinition getStop() {
        return stop;
    }

    public ExitOrderLegDefinition getLimit() {
        return limit;
    }

    private String normalize(String value) {
        return value == null ? "" : value;
    }
}
