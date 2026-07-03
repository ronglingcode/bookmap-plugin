package com.bookmap.plugin.rong.pricelines;

import java.awt.Color;
import java.util.UUID;

/**
 * Represents a horizontal price zone drawn on a Bookmap chart.
 */
public class PriceZone {

    public static final Color DEFAULT_COLOR = new Color(156, 163, 175);

    private final String id;
    private final String instrumentAlias;
    private final double lowPriceInTicks;
    private final double highPriceInTicks;
    private final double realLowPrice;
    private final double realHighPrice;
    private final String label;
    private final Color color;
    private final long createdAt;

    public PriceZone(
            String instrumentAlias,
            double lowPriceInTicks,
            double highPriceInTicks,
            double realLowPrice,
            double realHighPrice,
            String label,
            Color color) {
        this.id = UUID.randomUUID().toString();
        this.instrumentAlias = instrumentAlias;
        this.lowPriceInTicks = Math.min(lowPriceInTicks, highPriceInTicks);
        this.highPriceInTicks = Math.max(lowPriceInTicks, highPriceInTicks);
        this.realLowPrice = Math.min(realLowPrice, realHighPrice);
        this.realHighPrice = Math.max(realLowPrice, realHighPrice);
        this.label = label == null || label.trim().isEmpty() ? null : label.trim();
        this.color = color == null ? DEFAULT_COLOR : color;
        this.createdAt = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public String getInstrumentAlias() { return instrumentAlias; }
    public double getLowPriceInTicks() { return lowPriceInTicks; }
    public double getHighPriceInTicks() { return highPriceInTicks; }
    public double getRealLowPrice() { return realLowPrice; }
    public double getRealHighPrice() { return realHighPrice; }
    public String getLabel() { return label; }
    public Color getColor() { return color; }
    public long getCreatedAt() { return createdAt; }
}
