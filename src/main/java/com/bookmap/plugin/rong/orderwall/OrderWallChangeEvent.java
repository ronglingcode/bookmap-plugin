package com.bookmap.plugin.rong.orderwall;

import java.text.DecimalFormat;
import java.util.UUID;

/**
 * Immutable visual alert describing a large non-trade order book size change.
 */
public class OrderWallChangeEvent {

    private static final DecimalFormat PRICE_FORMAT = new DecimalFormat("0.########");

    public enum Type {
        ADDED,
        REDUCED,
        REPLACED_SMALLER
    }

    private final String id;
    private final String instrumentAlias;
    private final boolean bid;
    private final int priceTick;
    private final double realPrice;
    private final int previousSize;
    private final int currentSize;
    private final int tradedSize;
    private final Type type;
    private final long eventTimeNs;
    private final long createdAtMs;

    public OrderWallChangeEvent(String instrumentAlias, boolean bid, int priceTick, double realPrice,
                                int previousSize, int currentSize, int tradedSize, Type type,
                                long eventTimeNs, long createdAtMs) {
        this.id = UUID.randomUUID().toString();
        this.instrumentAlias = instrumentAlias;
        this.bid = bid;
        this.priceTick = priceTick;
        this.realPrice = realPrice;
        this.previousSize = previousSize;
        this.currentSize = currentSize;
        this.tradedSize = tradedSize;
        this.type = type;
        this.eventTimeNs = eventTimeNs;
        this.createdAtMs = createdAtMs;
    }

    public String getId() {
        return id;
    }

    public String getInstrumentAlias() {
        return instrumentAlias;
    }

    public boolean isBid() {
        return bid;
    }

    public int getPriceTick() {
        return priceTick;
    }

    public double getRealPrice() {
        return realPrice;
    }

    public int getPreviousSize() {
        return previousSize;
    }

    public int getCurrentSize() {
        return currentSize;
    }

    public int getTradedSize() {
        return tradedSize;
    }

    public Type getType() {
        return type;
    }

    public long getEventTimeNs() {
        return eventTimeNs;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public String getSideText() {
        return bid ? "BID" : "ASK";
    }

    public String getPriceText() {
        return PRICE_FORMAT.format(realPrice);
    }

    public String getTypeText() {
        switch (type) {
            case ADDED:
                return "ADDED";
            case REPLACED_SMALLER:
                return "CHANGED";
            case REDUCED:
            default:
                return "PULLED";
        }
    }

    public String getShortMessage() {
        return getSideText() + " " + getPriceText() + " " + getTypeText()
                + " " + formatSize(previousSize) + " -> " + formatSize(currentSize);
    }

    public String getLogMessage() {
        String message = instrumentAlias + " " + getShortMessage();
        if (tradedSize > 0 && type != Type.ADDED) {
            message += " (traded " + formatSize(tradedSize) + ")";
        }
        return message;
    }

    public static String formatSize(int size) {
        if (size == 0) {
            return "0";
        }
        if (Math.abs(size) < 1_000) {
            return Integer.toString(size);
        }
        double thousands = size / 1_000.0;
        if (Math.abs(thousands - Math.rint(thousands)) < 0.05) {
            return Integer.toString((int) Math.rint(thousands)) + "K";
        }
        return String.format("%.1fK", thousands);
    }
}
