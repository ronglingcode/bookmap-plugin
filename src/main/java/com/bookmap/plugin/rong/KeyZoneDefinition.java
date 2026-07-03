package com.bookmap.plugin.rong;

/**
 * Represents a price zone sent by an external client for a specific instrument's chart.
 */
public class KeyZoneDefinition {

    private final String instrument;
    private final double low;
    private final double high;
    private final String label;
    private final String color;

    public KeyZoneDefinition(String instrument, double low, double high, String label, String color) {
        this.instrument = SymbolUtils.cleanSymbol(instrument);
        this.low = Math.min(low, high);
        this.high = Math.max(low, high);
        this.label = normalizeOptional(label);
        this.color = normalizeOptional(color);
    }

    public String getInstrument() { return instrument; }
    public double getLow() { return low; }
    public double getHigh() { return high; }
    public String getLabel() { return label; }
    public String getColor() { return color; }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Override
    public String toString() {
        return instrument + " [" + low + ", " + high + "]"
                + (label != null ? " (" + label + ")" : "");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KeyZoneDefinition that = (KeyZoneDefinition) o;
        return Double.compare(that.low, low) == 0
                && Double.compare(that.high, high) == 0
                && instrument.equals(that.instrument)
                && (label == null ? that.label == null : label.equals(that.label))
                && (color == null ? that.color == null : color.equals(that.color));
    }

    @Override
    public int hashCode() {
        int result = instrument.hashCode();
        long lowBits = Double.doubleToLongBits(low);
        long highBits = Double.doubleToLongBits(high);
        result = 31 * result + (int) (lowBits ^ (lowBits >>> 32));
        result = 31 * result + (int) (highBits ^ (highBits >>> 32));
        result = 31 * result + (label != null ? label.hashCode() : 0);
        result = 31 * result + (color != null ? color.hashCode() : 0);
        return result;
    }
}
