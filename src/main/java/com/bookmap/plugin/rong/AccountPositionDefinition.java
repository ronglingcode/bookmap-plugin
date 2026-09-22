package com.bookmap.plugin.rong;

/**
 * Position snapshot pushed from ViteApp to the bmtrader account panel.
 */
public class AccountPositionDefinition {

    private final String symbol;
    private final double netQuantity;
    private final double averagePrice;
    private final double riskPercent;
    /**
     * Pre-formatted risk label from ViteApp (e.g. "-0.05R"). Null when the
     * sender is an older ViteApp that only provides {@link #riskPercent}.
     */
    private final String riskText;

    public AccountPositionDefinition(String symbol, double netQuantity, double averagePrice, double riskPercent,
            String riskText) {
        this.symbol = normalize(symbol);
        this.netQuantity = netQuantity;
        this.averagePrice = averagePrice;
        this.riskPercent = riskPercent;
        this.riskText = riskText;
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

    /**
     * @return the pre-formatted risk label, or null if the sender did not
     *         provide one (older ViteApp) and the caller should fall back to
     *         {@link #getRiskPercent()}.
     */
    public String getRiskText() {
        return riskText;
    }

    public boolean isOpen() {
        return netQuantity != 0;
    }

    private String normalize(String value) {
        return value == null ? "" : value;
    }
}
