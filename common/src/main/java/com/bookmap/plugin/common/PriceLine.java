package com.bookmap.plugin.common;

import java.awt.Color;
import java.util.UUID;

/**
 * Represents a horizontal price line drawn on a Bookmap chart.
 */
public class PriceLine {

    public enum LineType {
        // Manual (key+click) line types
        STOP_LOSS("Stop Loss", new Color(220, 50, 50), "s"),
        TAKE_PROFIT("Take Profit", new Color(50, 180, 50), "t"),
        ENTRY("Entry", new Color(50, 120, 220), "e"),

        // Auto-drawn indicator line types (defaultKey = null)
        PREMARKET_HIGH("PM High", new Color(255, 165, 0), null),
        PREMARKET_LOW("PM Low", new Color(180, 100, 255), null);

        public final String label;
        public final Color color;
        /** Key binding for manual types, null for auto-drawn indicators. */
        public final String defaultKey;

        LineType(String label, Color color, String defaultKey) {
            this.label = label;
            this.color = color;
            this.defaultKey = defaultKey;
        }

        /** Returns true if this type is drawn manually via key+click. */
        public boolean isManual() {
            return defaultKey != null;
        }
    }

    private final String id;
    private final String instrumentAlias;
    private final LineType type;
    private final double priceInTicks;
    private final double realPrice;
    private final long createdAt;

    public PriceLine(String instrumentAlias, LineType type, double priceInTicks, double realPrice) {
        this.id = UUID.randomUUID().toString();
        this.instrumentAlias = instrumentAlias;
        this.type = type;
        this.priceInTicks = priceInTicks;
        this.realPrice = realPrice;
        this.createdAt = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public String getInstrumentAlias() { return instrumentAlias; }
    public LineType getType() { return type; }
    public double getPriceInTicks() { return priceInTicks; }
    public double getRealPrice() { return realPrice; }
    public long getCreatedAt() { return createdAt; }
}
