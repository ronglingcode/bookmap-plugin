package com.bookmap.plugin.rong;

/**
 * One filled broker execution pushed from ViteApp to Bookmap.
 */
public class AccountExecutionDefinition {

    private final String symbol;
    private final double price;
    private final double quantity;
    private final boolean isBuy;
    private final boolean positionEffectIsOpen;
    private final long timeMs;

    public AccountExecutionDefinition(
            String symbol,
            double price,
            double quantity,
            boolean isBuy,
            boolean positionEffectIsOpen,
            long timeMs) {
        this.symbol = normalize(symbol);
        this.price = price;
        this.quantity = quantity;
        this.isBuy = isBuy;
        this.positionEffectIsOpen = positionEffectIsOpen;
        this.timeMs = timeMs;
    }

    public String getSymbol() {
        return symbol;
    }

    public double getPrice() {
        return price;
    }

    public double getQuantity() {
        return quantity;
    }

    public boolean isBuy() {
        return isBuy;
    }

    public boolean isPositionEffectIsOpen() {
        return positionEffectIsOpen;
    }

    public long getTimeMs() {
        return timeMs;
    }

    public long getTimeNs() {
        return timeMs * 1_000_000L;
    }

    private String normalize(String value) {
        return value == null ? "" : value;
    }
}
