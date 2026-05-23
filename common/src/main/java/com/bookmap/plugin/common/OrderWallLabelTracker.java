package com.bookmap.plugin.common;

/**
 * Tracks large liquidity walls and retains the largest observed size at each level.
 */
public class OrderWallLabelTracker {

    private final String instrumentAlias;
    private final double pips;
    private final OrderWallLabelStore store;
    private final double percentile;

    public OrderWallLabelTracker(String instrumentAlias, double pips, OrderWallLabelStore store,
                                 double percentile, int retainDistanceTicks) {
        this.instrumentAlias = instrumentAlias;
        this.pips = pips;
        this.store = store;
        this.percentile = percentile;
    }

    /**
     * Labels are created once a level reaches the configured percentile threshold.
     * When a level falls back below that threshold, the segment is frozen as a
     * historical wall so the peak size remains visible over the bright section.
     */
    public boolean onDepth(OrderBookState orderBook, boolean isBid, int priceTick, int size, long eventTimeNs) {
        OrderWallLabel existing = store.getActiveLabel(instrumentAlias, isBid, priceTick);
        int requiredSize = orderBook.getPercentileThreshold(percentile);
        boolean qualifiesNow = size > 0 && size >= requiredSize;

        if (!qualifiesNow && existing == null) {
            return false;
        }

        long timestampNs = eventTimeNs > 0 ? eventTimeNs : System.currentTimeMillis() * 1_000_000L;

        if (existing == null) {
            store.putLabel(new OrderWallLabel(
                    instrumentAlias,
                    isBid,
                    priceTick,
                    priceTick * pips,
                    size,
                    size,
                    timestampNs,
                    timestampNs,
                    System.currentTimeMillis()));
            return true;
        }

        if (!existing.isActive() && !qualifiesNow) {
            return false;
        }

        // Peak size is monotonic for a wall segment: once we have seen a larger wall,
        // never overwrite that historical maximum with a smaller reloaded wall.
        int peakSize = Math.max(existing.getPeakSize(), size);
        int currentSize = qualifiesNow ? size : 0;
        long startTimeNs = existing.isActive() ? existing.getStartTimeNs() : timestampNs;
        long endTimeNs = timestampNs;

        if (existing.getCurrentSize() == currentSize
                && existing.getPeakSize() == peakSize
                && existing.getEndTimeNs() == endTimeNs
                && existing.getStartTimeNs() == startTimeNs) {
            return false;
        }

        OrderWallLabel updated = new OrderWallLabel(
                existing.getId(),
                instrumentAlias,
                isBid,
                priceTick,
                priceTick * pips,
                currentSize,
                peakSize,
                startTimeNs,
                endTimeNs,
                System.currentTimeMillis(),
                existing.hasBeenDisplayed());
        store.putLabel(updated);
        return true;
    }

    /**
     * Historical labels persist for the lifetime of the addon session.
     */
    public boolean cleanup(int currentPriceTick) {
        return false;
    }
}
