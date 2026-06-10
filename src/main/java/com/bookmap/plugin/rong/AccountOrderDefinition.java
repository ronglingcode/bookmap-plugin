package com.bookmap.plugin.rong;

/**
 * One open broker order pushed from ViteApp to the Rong account panel.
 */
public class AccountOrderDefinition {

    private final String symbol;
    private final String orderId;
    private final String role;
    private final String orderType;
    private final double price;
    private final double quantity;
    private final boolean isBuy;
    private final String source;
    private final String parentOrderId;
    private final int pairIndex;

    public AccountOrderDefinition(
            String symbol,
            String orderId,
            String role,
            String orderType,
            double price,
            double quantity,
            boolean isBuy,
            String source,
            String parentOrderId,
            int pairIndex) {
        this.symbol = normalize(symbol);
        this.orderId = normalize(orderId);
        this.role = normalize(role);
        this.orderType = normalize(orderType);
        this.price = price;
        this.quantity = quantity;
        this.isBuy = isBuy;
        this.source = normalize(source);
        this.parentOrderId = normalize(parentOrderId);
        this.pairIndex = pairIndex;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getRole() {
        return role;
    }

    public String getOrderType() {
        return orderType;
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

    public String getSource() {
        return source;
    }

    public String getParentOrderId() {
        return parentOrderId;
    }

    public int getPairIndex() {
        return pairIndex;
    }

    private String normalize(String value) {
        return value == null ? "" : value;
    }
}
