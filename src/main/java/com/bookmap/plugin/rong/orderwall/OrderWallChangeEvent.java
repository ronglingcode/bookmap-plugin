package com.bookmap.plugin.rong.orderwall;

import java.text.DecimalFormat;
import java.util.UUID;

/** Immutable description of a material order-book size change at one price. */
public class OrderWallChangeEvent {

    private static final DecimalFormat PRICE_FORMAT = new DecimalFormat("0.########");

    public enum Type {
        ADDED,
        INCREASED,
        REDUCED,
        REPLACED_SMALLER,
        OFFER_BREAKOUT,
        BID_BREAKDOWN,
        OFFER_MOVED_UP,
        OFFER_MOVED_DOWN,
        BID_MOVED_UP,
        BID_MOVED_DOWN
    }

    public enum LabelType {
        BID_UP("BID UP"),
        BID_PULL("BID PULL"),
        BID_DOWN("BID DOWN"),
        OFFER_UP("OFFER UP"),
        OFFER_DOWN("OFFER DOWN"),
        OFFER_PULL("OFFER PULL");

        private final String text;

        LabelType(String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }
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
    private final int effectiveThreshold;
    private final boolean crossedThreshold;
    private final boolean deltaExceedsThreshold;
    private final boolean tradeConsumption;
    private final boolean withinDayRange;
    private final double dayHigh;
    private final double dayLow;
    private final int previousPriceTick;
    private final double previousRealPrice;
    private final int movedSize;

    public OrderWallChangeEvent(String instrumentAlias, boolean bid, int priceTick, double realPrice,
                                int previousSize, int currentSize, int tradedSize, Type type,
                                long eventTimeNs, long createdAtMs) {
        this(instrumentAlias, bid, priceTick, realPrice, previousSize, currentSize, tradedSize, type,
                eventTimeNs, createdAtMs, 0, false, false, false, false,
                Double.NaN, Double.NaN, priceTick, realPrice, 0);
    }

    public OrderWallChangeEvent(String instrumentAlias, boolean bid, int priceTick, double realPrice,
                                int previousSize, int currentSize, int tradedSize, Type type,
                                long eventTimeNs, long createdAtMs, int effectiveThreshold,
                                boolean crossedThreshold, boolean deltaExceedsThreshold,
                                boolean tradeConsumption, boolean withinDayRange,
                                double dayHigh, double dayLow) {
        this(instrumentAlias, bid, priceTick, realPrice, previousSize, currentSize, tradedSize, type,
                eventTimeNs, createdAtMs, effectiveThreshold, crossedThreshold,
                deltaExceedsThreshold, tradeConsumption, withinDayRange, dayHigh, dayLow,
                priceTick, realPrice, 0);
    }

    public OrderWallChangeEvent(String instrumentAlias, boolean bid, int priceTick, double realPrice,
                                int previousSize, int currentSize, int tradedSize, Type type,
                                long eventTimeNs, long createdAtMs, int effectiveThreshold,
                                boolean crossedThreshold, boolean deltaExceedsThreshold,
                                boolean tradeConsumption, boolean withinDayRange,
                                double dayHigh, double dayLow, int previousPriceTick,
                                double previousRealPrice, int movedSize) {
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
        this.effectiveThreshold = effectiveThreshold;
        this.crossedThreshold = crossedThreshold;
        this.deltaExceedsThreshold = deltaExceedsThreshold;
        this.tradeConsumption = tradeConsumption;
        this.withinDayRange = withinDayRange;
        this.dayHigh = dayHigh;
        this.dayLow = dayLow;
        this.previousPriceTick = previousPriceTick;
        this.previousRealPrice = previousRealPrice;
        this.movedSize = movedSize;
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

    public int getEffectiveThreshold() {
        return effectiveThreshold;
    }

    public int getSizeDelta() {
        return currentSize - previousSize;
    }

    public boolean isCrossedThreshold() {
        return crossedThreshold;
    }

    public boolean isDeltaExceedsThreshold() {
        return deltaExceedsThreshold;
    }

    public boolean isTradeConsumption() {
        return tradeConsumption;
    }

    public boolean isWithinDayRange() {
        return withinDayRange;
    }

    public double getDayHigh() {
        return dayHigh;
    }

    public double getDayLow() {
        return dayLow;
    }

    public int getPreviousPriceTick() {
        return previousPriceTick;
    }

    public double getPreviousRealPrice() {
        return previousRealPrice;
    }

    public int getMovedSize() {
        return movedSize;
    }

    public boolean isMovedOrder() {
        return type == Type.OFFER_MOVED_UP
                || type == Type.OFFER_MOVED_DOWN
                || type == Type.BID_MOVED_UP
                || type == Type.BID_MOVED_DOWN;
    }

    public boolean isMaterialChange() {
        return crossedThreshold || deltaExceedsThreshold;
    }

    public boolean isActiveLiquidityAlert() {
        return isMaterialChange() && withinDayRange && !tradeConsumption;
    }

    public LabelType getLabelType() {
        switch (type) {
            case BID_MOVED_UP:
                return LabelType.BID_UP;
            case BID_MOVED_DOWN:
                return LabelType.BID_DOWN;
            case OFFER_MOVED_UP:
                return LabelType.OFFER_UP;
            case OFFER_MOVED_DOWN:
                return LabelType.OFFER_DOWN;
            default:
                break;
        }
        boolean increase = currentSize > previousSize;
        if (bid) {
            return increase ? LabelType.BID_UP : LabelType.BID_PULL;
        }
        return increase ? LabelType.OFFER_DOWN : LabelType.OFFER_PULL;
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
            case INCREASED:
                return "INCREASED";
            case REPLACED_SMALLER:
                return "CHANGED";
            case OFFER_BREAKOUT:
                return "BREAKOUT";
            case BID_BREAKDOWN:
                return "BREAKDOWN";
            case OFFER_MOVED_UP:
            case BID_MOVED_UP:
                return "MOVED UP";
            case OFFER_MOVED_DOWN:
            case BID_MOVED_DOWN:
                return "MOVED DOWN";
            case REDUCED:
            default:
                return "PULLED";
        }
    }

    public String getShortMessage() {
        if (isMovedOrder()) {
            return getLabelType().getText() + " "
                    + PRICE_FORMAT.format(previousRealPrice) + " -> " + getPriceText()
                    + " " + formatSize(movedSize);
        }
        if (isWallBreak()) {
            return getSideText() + " " + getPriceText() + " " + getTypeText()
                    + " filled " + formatSize(previousSize);
        }
        String label = isMaterialChange()
                ? getLabelType().getText()
                : getSideText() + " " + getTypeText();
        return getPriceText() + " " + label
                + " " + formatSize(previousSize) + " -> " + formatSize(currentSize);
    }

    public String getLogMessage() {
        String message = instrumentAlias + " " + getShortMessage();
        if (tradedSize > 0 && type != Type.ADDED && !isWallBreak()) {
            message += " (traded " + formatSize(tradedSize) + ")";
        }
        return message;
    }

    public boolean isWallBreak() {
        return type == Type.OFFER_BREAKOUT || type == Type.BID_BREAKDOWN;
    }

    public static String formatSize(int size) {
        if (size == 0) {
            return "0";
        }
        if (Math.abs(size) < 1_000) {
            return Integer.toString(size);
        }
        return Long.toString(Math.round(size / 1_000.0)) + "K";
    }
}
