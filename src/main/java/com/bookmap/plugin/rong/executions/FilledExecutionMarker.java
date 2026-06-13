package com.bookmap.plugin.rong.executions;

import java.util.Locale;

/**
 * Renderable filled execution marker in Bookmap data coordinates.
 */
public class FilledExecutionMarker {

    private final String id;
    private final String instrumentAlias;
    private final double priceInTicks;
    private final double realPrice;
    private final double quantity;
    private final boolean buy;
    private final boolean opening;
    private final long timeNs;

    public FilledExecutionMarker(
            String instrumentAlias,
            double priceInTicks,
            double realPrice,
            double quantity,
            boolean buy,
            boolean opening,
            long timeNs) {
        this.instrumentAlias = normalize(instrumentAlias);
        this.priceInTicks = priceInTicks;
        this.realPrice = realPrice;
        this.quantity = quantity;
        this.buy = buy;
        this.opening = opening;
        this.timeNs = timeNs;
        this.id = createId();
    }

    public String getId() {
        return id;
    }

    public String getInstrumentAlias() {
        return instrumentAlias;
    }

    public double getPriceInTicks() {
        return priceInTicks;
    }

    public double getRealPrice() {
        return realPrice;
    }

    public double getQuantity() {
        return quantity;
    }

    public boolean isBuy() {
        return buy;
    }

    public boolean isOpening() {
        return opening;
    }

    public long getTimeNs() {
        return timeNs;
    }

    private String createId() {
        return instrumentAlias
                + "|" + timeNs
                + "|" + String.format(Locale.US, "%.8f", realPrice)
                + "|" + String.format(Locale.US, "%.4f", quantity)
                + "|" + buy
                + "|" + opening;
    }

    private String normalize(String value) {
        return value == null ? "" : value;
    }
}
