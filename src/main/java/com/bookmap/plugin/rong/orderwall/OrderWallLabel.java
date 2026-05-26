package com.bookmap.plugin.rong.orderwall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Immutable snapshot of a tracked liquidity wall label.
 */
public class OrderWallLabel {

    static final int DISPLAY_SIZE_UNIT = 1_000;

    private final String id;
    private final String instrumentAlias;
    private final boolean bid;
    private final int priceTick;
    private final double realPrice;
    private final int currentSize;
    private final int peakSize;
    private final long startTimeNs;
    private final long endTimeNs;
    private final long updatedAt;
    private final boolean hasBeenDisplayed;
    private final List<Integer> sizePath;

    public OrderWallLabel(String instrumentAlias, boolean bid, int priceTick, double realPrice,
                          int currentSize, int peakSize, long startTimeNs, long endTimeNs, long updatedAt) {
        this(UUID.randomUUID().toString(), instrumentAlias, bid, priceTick, realPrice,
                currentSize, peakSize, startTimeNs, endTimeNs, updatedAt, false);
    }

    public OrderWallLabel(String id, String instrumentAlias, boolean bid, int priceTick, double realPrice,
                          int currentSize, int peakSize, long startTimeNs, long endTimeNs, long updatedAt,
                          boolean hasBeenDisplayed) {
        this(id, instrumentAlias, bid, priceTick, realPrice, currentSize, peakSize,
                startTimeNs, endTimeNs, updatedAt, hasBeenDisplayed, defaultSizePath(peakSize));
    }

    public OrderWallLabel(String id, String instrumentAlias, boolean bid, int priceTick, double realPrice,
                          int currentSize, int peakSize, long startTimeNs, long endTimeNs, long updatedAt,
                          boolean hasBeenDisplayed, List<Integer> sizePath) {
        this.id = id;
        this.instrumentAlias = instrumentAlias;
        this.bid = bid;
        this.priceTick = priceTick;
        this.realPrice = realPrice;
        this.currentSize = currentSize;
        this.peakSize = peakSize;
        this.startTimeNs = startTimeNs;
        this.endTimeNs = endTimeNs;
        this.updatedAt = updatedAt;
        this.hasBeenDisplayed = hasBeenDisplayed;
        this.sizePath = Collections.unmodifiableList(new ArrayList<>(sizePath));
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

    public int getCurrentSize() {
        return currentSize;
    }

    public int getPeakSize() {
        return peakSize;
    }

    public long getStartTimeNs() {
        return startTimeNs;
    }

    public long getEndTimeNs() {
        return endTimeNs;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public boolean hasBeenDisplayed() {
        return hasBeenDisplayed;
    }

    public List<Integer> getSizePath() {
        return sizePath;
    }

    public boolean isActive() {
        return currentSize > 0;
    }

    public String getKey() {
        return id;
    }

    public String getPriceKey() {
        return buildKey(bid, priceTick);
    }

    public static String buildKey(boolean bid, int priceTick) {
        return (bid ? "B:" : "A:") + priceTick;
    }

    static int toDisplaySize(int size) {
        return size / DISPLAY_SIZE_UNIT;
    }

    private static List<Integer> defaultSizePath(int size) {
        int displaySize = toDisplaySize(size);
        if (displaySize <= 0) {
            return Collections.emptyList();
        }
        return Collections.singletonList(displaySize);
    }
}
