package com.bookmap.plugin.rong.pricelines;

import java.awt.Color;
import java.util.UUID;

/**
 * Represents a horizontal price line drawn on a Bookmap chart.
 */
public class PriceLine {

    public enum LineType {
        PREMARKET_HIGH("PM High", new Color(255, 165, 0)),
        PREMARKET_LOW("PM Low", new Color(180, 100, 255)),
        KEY_LEVEL("Key Level", new Color(255, 215, 0)),
        EXIT_ORDER("Exit", new Color(120, 120, 120)),
        ENTRY_ORDER("Entry", new Color(70, 170, 255)),

        // Camarilla Pivot levels - resistance (warm colors)
        CAM_R1("R1", new Color(255, 200, 200)),
        CAM_R2("R2", new Color(255, 160, 160)),
        CAM_R3("R3", new Color(255, 120, 120)),
        CAM_R4("R4", new Color(220, 80, 80)),
        CAM_R5("R5", new Color(200, 50, 50)),
        CAM_R6("R6", new Color(180, 30, 30)),

        // Camarilla Pivot levels - support (cool colors)
        CAM_S1("S1", new Color(200, 220, 255)),
        CAM_S2("S2", new Color(160, 200, 255)),
        CAM_S3("S3", new Color(120, 170, 255)),
        CAM_S4("S4", new Color(80, 140, 220)),
        CAM_S5("S5", new Color(50, 110, 200)),
        CAM_S6("S6", new Color(30, 80, 180));

        public final String label;
        public final Color color;

        LineType(String label, Color color) {
            this.label = label;
            this.color = color;
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
    private final Color customColor;

    public PriceLine(String instrumentAlias, LineType type, double priceInTicks, double realPrice) {
        this(instrumentAlias, type, priceInTicks, realPrice, null);
    }

    public PriceLine(String instrumentAlias, LineType type, double priceInTicks, double realPrice,
                     String customLabel) {
        this(instrumentAlias, type, priceInTicks, realPrice, customLabel, null);
    }

    public PriceLine(String instrumentAlias, LineType type, double priceInTicks, double realPrice,
                     String customLabel, Color customColor) {
        this.id = UUID.randomUUID().toString();
        this.instrumentAlias = instrumentAlias;
        this.type = type;
        this.priceInTicks = priceInTicks;
        this.realPrice = realPrice;
        this.createdAt = System.currentTimeMillis();
        this.customLabel = customLabel;
        this.customColor = customColor;
    }

    public String getId() { return id; }
    public String getInstrumentAlias() { return instrumentAlias; }
    public LineType getType() { return type; }
    public double getPriceInTicks() { return priceInTicks; }
    public double getRealPrice() { return realPrice; }
    public long getCreatedAt() { return createdAt; }
    /** Returns custom label text, or null if default LineType.label should be used. */
    public String getCustomLabel() { return customLabel; }
    /** Returns custom line color, or null if LineType.color should be used. */
    public Color getCustomColor() { return customColor; }
}
