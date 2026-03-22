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
        PREMARKET_LOW("PM Low", new Color(180, 100, 255), null),
        VWAP("VWAP", new Color(0, 180, 180), null),
        KEY_LEVEL("Key Level", new Color(255, 215, 0), null),

        // Camarilla Pivot levels — resistance (warm colors)
        CAM_R1("R1", new Color(255, 200, 200), null),
        CAM_R2("R2", new Color(255, 160, 160), null),
        CAM_R3("R3", new Color(255, 120, 120), null),
        CAM_R4("R4", new Color(220, 80, 80), null),
        CAM_R5("R5", new Color(200, 50, 50), null),
        CAM_R6("R6", new Color(180, 30, 30), null),

        // Camarilla Pivot levels — support (cool colors)
        CAM_S1("S1", new Color(200, 220, 255), null),
        CAM_S2("S2", new Color(160, 200, 255), null),
        CAM_S3("S3", new Color(120, 170, 255), null),
        CAM_S4("S4", new Color(80, 140, 220), null),
        CAM_S5("S5", new Color(50, 110, 200), null),
        CAM_S6("S6", new Color(30, 80, 180), null);

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

    /**
     * Optional custom label that overrides LineType.label when rendering.
     * Used by KEY_LEVEL lines so each level can have its own descriptive text.
     * Null means use the default LineType.label.
     */
    private final String customLabel;

    public PriceLine(String instrumentAlias, LineType type, double priceInTicks, double realPrice) {
        this(instrumentAlias, type, priceInTicks, realPrice, null);
    }

    public PriceLine(String instrumentAlias, LineType type, double priceInTicks, double realPrice,
                     String customLabel) {
        this.id = UUID.randomUUID().toString();
        this.instrumentAlias = instrumentAlias;
        this.type = type;
        this.priceInTicks = priceInTicks;
        this.realPrice = realPrice;
        this.createdAt = System.currentTimeMillis();
        this.customLabel = customLabel;
    }

    public String getId() { return id; }
    public String getInstrumentAlias() { return instrumentAlias; }
    public LineType getType() { return type; }
    public double getPriceInTicks() { return priceInTicks; }
    public double getRealPrice() { return realPrice; }
    public long getCreatedAt() { return createdAt; }
    /** Returns custom label text, or null if default LineType.label should be used. */
    public String getCustomLabel() { return customLabel; }
}
