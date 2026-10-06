package com.bookmap.plugin.rong;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Maintains the full order book state (both bids and asks) as received
 * from Bookmap's DepthDataListener. Each price level stores the total
 * number of shares of limit orders at that level.
 *
 * Prices are in tick units (as provided by onDepth). Convert to real
 * prices by multiplying by instrumentInfo.pips.
 */
public class OrderBookState {

    // Bids sorted descending (best bid = first entry)
    private final TreeMap<Integer, Integer> bids = new TreeMap<>(Comparator.reverseOrder());
    // Asks sorted ascending (best ask = first entry)
    private final TreeMap<Integer, Integer> asks = new TreeMap<>();
    // Histogram of depth sizes across both sides for faster percentile lookups.
    private final TreeMap<Integer, Integer> sizeCounts = new TreeMap<>();
    private int totalLevels;
    private long totalQuantity;
    private double weightedPriceSum;

    /**
     * Update a price level with an absolute size.
     * Called from onDepth(). size == 0 means the level is empty.
     */
    public synchronized void update(boolean isBid, int price, int size) {
        if (size < 0) {
            throw new IllegalArgumentException("Depth size must be nonnegative");
        }
        TreeMap<Integer, Integer> book = isBid ? bids : asks;
        Integer previousSize = book.get(price);

        if (previousSize != null) {
            totalQuantity -= previousSize;
            weightedPriceSum -= (double) price * previousSize;
            decrementSizeCount(previousSize);
            totalLevels--;
        }

        if (size == 0) {
            book.remove(price);
        } else {
            totalQuantity += size;
            weightedPriceSum += (double) price * size;
            book.put(price, size);
            incrementSizeCount(size);
            totalLevels++;
        }
        if (totalQuantity == 0) {
            weightedPriceSum = 0;
        }
    }

    /** Quantity-weighted price of all received bids and asks, in Bookmap tick units.
     * An empty book returns NaN so the chart displays a gap rather than a stale price.
     */
    public synchronized double getWeightedAveragePriceLevel() {
        return totalQuantity == 0 ? Double.NaN : weightedPriceSum / totalQuantity;
    }

    /** Current midpoint in ticks, or the available positive best quote for a one-sided book. */
    synchronized double getWeightedAverageReferencePriceLevel() {
        Integer bid = getBestBid(), ask = getBestAsk();
        boolean hasBid = bid != null && bid > 0;
        boolean hasAsk = ask != null && ask > 0;
        if (hasBid && hasAsk) return (bid.doubleValue() + ask.doubleValue()) / 2;
        if (hasBid) return bid;
        if (hasAsk) return ask;
        return Double.NaN;
    }

    /** Inclusive price range. Excluded levels remain in the book for other consumers. */
    synchronized double getWeightedAveragePriceLevel(double minimum, double maximum) {
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || minimum > maximum
                || minimum > Integer.MAX_VALUE || maximum < Integer.MIN_VALUE) return Double.NaN;
        int lower = (int) Math.ceil(minimum), upper = (int) Math.floor(maximum);
        if (lower > upper) return Double.NaN;
        long quantity = totalQuantity;
        double sum = weightedPriceSum;
        // Work only through the excluded tails, rather than scanning the full book per update.
        for (NavigableMap<Integer, Integer> side : java.util.Arrays.asList(bids.descendingMap(), asks)) {
            for (Map.Entry<Integer, Integer> level : side.headMap(lower, false).entrySet()) {
                quantity -= level.getValue();
                sum -= (double) level.getKey() * level.getValue();
            }
            for (Map.Entry<Integer, Integer> level : side.tailMap(upper, false).entrySet()) {
                quantity -= level.getValue();
                sum -= (double) level.getKey() * level.getValue();
            }
        }
        return quantity == 0 ? Double.NaN : sum / quantity;
    }

    /** Independently recomputes the average from the stored levels for runtime diagnostics. */
    synchronized WeightedAverageAudit auditWeightedAverage() {
        return auditWeightedAverage(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    synchronized WeightedAverageAudit auditWeightedAverage(double minimum, double maximum) {
        long bidQuantity = 0, askQuantity = 0;
        long excludedQuantity = 0;
        double sum = 0;
        int minPrice = Integer.MAX_VALUE, maxPrice = Integer.MIN_VALUE;
        List<AuditLevel> levels = new ArrayList<>();
        for (boolean bid : new boolean[]{true, false}) {
            for (Map.Entry<Integer, Integer> entry : (bid ? bids : asks).entrySet()) {
                int price = entry.getKey(), size = entry.getValue();
                if (!(price >= minimum && price <= maximum)) {
                    excludedQuantity += size;
                    continue;
                }
                if (bid) bidQuantity += size; else askQuantity += size;
                sum += (double) price * size;
                minPrice = Math.min(minPrice, price);
                maxPrice = Math.max(maxPrice, price);
                levels.add(new AuditLevel(bid, price, size));
            }
        }
        levels.sort(Comparator.comparingDouble((AuditLevel level) ->
                Math.abs((double) level.price * level.size)).reversed());
        long quantity = bidQuantity + askQuantity;
        return new WeightedAverageAudit(quantity == 0 ? Double.NaN : sum / quantity,
                bidQuantity, askQuantity, quantity == 0 ? Double.NaN : minPrice,
                quantity == 0 ? Double.NaN : maxPrice,
                new ArrayList<>(levels.subList(0, Math.min(5, levels.size()))), excludedQuantity);
    }

    static final class AuditLevel {
        final boolean bid;
        final int price, size;
        AuditLevel(boolean bid, int price, int size) {
            this.bid = bid; this.price = price; this.size = size;
        }
    }

    static final class WeightedAverageAudit {
        final double average, minPrice, maxPrice;
        final long bidQuantity, askQuantity;
        final long excludedQuantity;
        final List<AuditLevel> largestContributions;
        WeightedAverageAudit(double average, long bidQuantity, long askQuantity,
                double minPrice, double maxPrice, List<AuditLevel> largestContributions, long excludedQuantity) {
            this.average = average; this.bidQuantity = bidQuantity; this.askQuantity = askQuantity;
            this.minPrice = minPrice; this.maxPrice = maxPrice;
            this.largestContributions = Collections.unmodifiableList(largestContributions);
            this.excludedQuantity = excludedQuantity;
        }
    }

    /** Best bid price tick, or null if no bids. */
    public Integer getBestBid() {
        return bids.isEmpty() ? null : bids.firstKey();
    }

    /** Best ask price tick, or null if no asks. */
    public Integer getBestAsk() {
        return asks.isEmpty() ? null : asks.firstKey();
    }

    /** Best bid size, or 0 if no bids. */
    public int getBestBidSize() {
        if (bids.isEmpty()) return 0;
        return bids.firstEntry().getValue();
    }

    /** Best ask size, or 0 if no asks. */
    public int getBestAskSize() {
        if (asks.isEmpty()) return 0;
        return asks.firstEntry().getValue();
    }

    /** Size at a specific price level, or 0 if not present. */
    public int getSizeAt(boolean isBid, int price) {
        TreeMap<Integer, Integer> book = isBid ? bids : asks;
        return book.getOrDefault(price, 0);
    }

    /**
     * Top N bid levels (best first). Returns a snapshot.
     * If fewer than N levels exist, returns all available.
     */
    public NavigableMap<Integer, Integer> getBidDepth(int levels) {
        return getTopLevels(bids, levels);
    }

    /**
     * Top N ask levels (best first). Returns a snapshot.
     * If fewer than N levels exist, returns all available.
     */
    public NavigableMap<Integer, Integer> getAskDepth(int levels) {
        return getTopLevels(asks, levels);
    }

    /** Total shares across all bid levels. */
    public long getTotalBidSize() {
        return bids.values().stream().mapToLong(Integer::longValue).sum();
    }

    /** Total shares across all ask levels. */
    public long getTotalAskSize() {
        return asks.values().stream().mapToLong(Integer::longValue).sum();
    }

    /** Number of bid price levels. */
    public int getBidLevelCount() {
        return bids.size();
    }

    /** Number of ask price levels. */
    public int getAskLevelCount() {
        return asks.size();
    }

    /** Unmodifiable view of all bids (descending by price). */
    public NavigableMap<Integer, Integer> getBids() {
        return Collections.unmodifiableNavigableMap(bids);
    }

    /** Unmodifiable view of all asks (ascending by price). */
    public NavigableMap<Integer, Integer> getAsks() {
        return Collections.unmodifiableNavigableMap(asks);
    }

    /** Thread-safe copy of every level on one side of the book. */
    public synchronized NavigableMap<Integer, Integer> getLevelsSnapshot(boolean isBid) {
        TreeMap<Integer, Integer> source = isBid ? bids : asks;
        TreeMap<Integer, Integer> copy = new TreeMap<>(source.comparator());
        copy.putAll(source);
        return Collections.unmodifiableNavigableMap(copy);
    }

    /**
     * Calculate the size threshold at the given percentile across all levels.
     * @param percentile 0-100 (e.g., 90 means only top 10% of sizes pass)
     * @return the size value at that percentile, or 0 if book is empty
     */
    public int getPercentileThreshold(double percentile) {
        if (totalLevels == 0) return 0;

        int index = (int) Math.ceil(percentile / 100.0 * totalLevels) - 1;
        index = Math.max(0, Math.min(index, totalLevels - 1));

        int cumulative = 0;
        for (Map.Entry<Integer, Integer> entry : sizeCounts.entrySet()) {
            cumulative += entry.getValue();
            if (cumulative > index) {
                return entry.getKey();
            }
        }
        return sizeCounts.lastKey();
    }

    /**
     * Returns the largest depth-level sizes across both sides of the book.
     * Duplicate sizes are retained because each occurrence represents a
     * separate price level.
     */
    public synchronized List<Integer> getLargestLevelSizes(int limit) {
        if (limit <= 0 || totalLevels == 0) {
            return Collections.emptyList();
        }

        List<Integer> largestSizes = new ArrayList<>(Math.min(limit, totalLevels));
        for (Map.Entry<Integer, Integer> entry : sizeCounts.descendingMap().entrySet()) {
            int occurrences = Math.min(entry.getValue(), limit - largestSizes.size());
            for (int i = 0; i < occurrences; i++) {
                largestSizes.add(entry.getKey());
            }
            if (largestSizes.size() == limit) {
                break;
            }
        }
        return Collections.unmodifiableList(largestSizes);
    }

    /**
     * Returns the effective wall-size threshold for this book.
     * The configured absolute floor remains the lower bound when the book is sparse.
     */
    public synchronized int getSizeThreshold(int thresholdFloor, double percentile) {
        int percentileThreshold = percentile > 0
                ? getPercentileThreshold(percentile)
                : 0;
        return combineSizeThresholds(thresholdFloor, percentileThreshold);
    }

    /**
     * Combines an already-calculated percentile threshold with the absolute floor.
     * Consumers that maintain their own order-book histogram can use this helper.
     */
    public static int combineSizeThresholds(int thresholdFloor, int percentileThreshold) {
        return Math.max(Math.max(0, thresholdFloor), Math.max(0, percentileThreshold));
    }

    private NavigableMap<Integer, Integer> getTopLevels(TreeMap<Integer, Integer> book, int levels) {
        TreeMap<Integer, Integer> result = new TreeMap<>(book.comparator());
        int count = 0;
        for (Map.Entry<Integer, Integer> entry : book.entrySet()) {
            if (count >= levels) break;
            result.put(entry.getKey(), entry.getValue());
            count++;
        }
        return result;
    }

    private void incrementSizeCount(int size) {
        sizeCounts.merge(size, 1, Integer::sum);
    }

    private void decrementSizeCount(int size) {
        sizeCounts.computeIfPresent(size, (ignored, count) -> count > 1 ? count - 1 : null);
    }
}
