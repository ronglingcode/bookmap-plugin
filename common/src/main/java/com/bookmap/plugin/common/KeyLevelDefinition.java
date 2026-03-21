package com.bookmap.plugin.common;

/**
 * Represents a predefined price level that the user wants drawn on a specific instrument's chart.
 *
 * <p>Key levels are significant price points identified from higher timeframe analysis
 * (e.g., daily chart support/resistance levels). Unlike key+click lines which are placed
 * interactively, key levels are defined in advance — either via a JSON config file or
 * through the settings panel at runtime.</p>
 *
 * <p>This is a raw definition that holds the real price (e.g., $180.00). Conversion to
 * tick coordinates happens later in {@link KeyLevelManager} when the instrument's
 * {@code pips} multiplier becomes available from Bookmap's {@code InstrumentInfo}.</p>
 *
 * <p>Each definition is instrument-specific: a $180 key level on NVDA does NOT appear
 * on any other instrument's chart.</p>
 */
public class KeyLevelDefinition {

    /** Instrument alias as it appears in Bookmap (e.g., "NVDA", "ESM5", "AAPL"). */
    private final String instrument;

    /** Price in real/display units (e.g., 180.00 for $180). */
    private final double price;

    /**
     * Optional descriptive label (e.g., "major support", "daily resistance").
     * If null, the default "Key Level" label from LineType will be used.
     */
    private final String label;

    public KeyLevelDefinition(String instrument, double price, String label) {
        this.instrument = instrument;
        this.price = price;
        this.label = label;
    }

    public String getInstrument() { return instrument; }
    public double getPrice() { return price; }
    public String getLabel() { return label; }

    @Override
    public String toString() {
        return instrument + " @ " + price + (label != null ? " (" + label + ")" : "");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KeyLevelDefinition that = (KeyLevelDefinition) o;
        return Double.compare(that.price, price) == 0
                && instrument.equals(that.instrument)
                && (label == null ? that.label == null : label.equals(that.label));
    }

    @Override
    public int hashCode() {
        int result = instrument.hashCode();
        long priceBits = Double.doubleToLongBits(price);
        result = 31 * result + (int) (priceBits ^ (priceBits >>> 32));
        result = 31 * result + (label != null ? label.hashCode() : 0);
        return result;
    }
}
