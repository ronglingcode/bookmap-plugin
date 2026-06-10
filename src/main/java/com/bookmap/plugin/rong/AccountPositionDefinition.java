package com.bookmap.plugin.rong;

/**
 * Position snapshot pushed from ViteApp to the Rong account panel.
 */
public class AccountPositionDefinition {

    private final String symbol;
    private final double netQuantity;
    private final double averagePrice;
    private final double riskPercent;

    public AccountPositionDefinition(String symbol, double netQuantity, double averagePrice, double riskPercent) {
        this.symbol = normalize(symbol);
        this.netQuantity = netQuantity;
        this.averagePrice = averagePrice;
        this.riskPercent = riskPercent;
    }

    public String getSymbol() {
        return symbol;
    }

    public double getNetQuantity() {
        return netQuantity;
    }

    public double getAveragePrice() {
        return averagePrice;
    }

    public double getRiskPercent() {
        return riskPercent;
    }

    public boolean isOpen() {
        return netQuantity != 0;
    }

    private String normalize(String value) {
        return value == null ? "" : value;
    }
}
