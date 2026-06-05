package com.bookmap.plugin.rong;

/**
 * One stop or limit leg from a ViteApp exit-order pair config.
 */
public class ExitOrderLegDefinition {

    private final String orderId;
    private final double price;
    private final int quantity;
    private final boolean isBuy;

    public ExitOrderLegDefinition(String orderId, double price, int quantity, boolean isBuy) {
        this.orderId = normalize(orderId);
        this.price = price;
        this.quantity = quantity;
        this.isBuy = isBuy;
    }

    public String getOrderId() {
        return orderId;
    }

    public double getPrice() {
        return price;
    }

    public int getQuantity() {
        return quantity;
    }

    public boolean isBuy() {
        return isBuy;
    }

    private String normalize(String value) {
        return value == null ? "" : value;
    }
}
