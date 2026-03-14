package com.bookmap.plugin.wallbreakout;

import java.util.Collections;
import java.util.Comparator;
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

    /**
     * Update a price level with an absolute size.
     * Called from onDepth(). size == 0 means the level is empty.
     */
    public void update(boolean isBid, int price, int size) {
        TreeMap<Integer, Integer> book = isBid ? bids : asks;
        if (size == 0) {
            book.remove(price);
        } else {
            book.put(price, size);
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

    /**
     * Serialize the top N levels of bids and asks to JSON.
     * @param levels number of levels per side
     * @param pips multiplier to convert tick prices to real prices
     */
    public String toJson(int levels, double pips) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"orderbook\",\"timestamp\":").append(System.currentTimeMillis());
        sb.append(",\"bids\":[");
        appendLevels(sb, bids, levels, pips);
        sb.append("],\"asks\":[");
        appendLevels(sb, asks, levels, pips);
        sb.append("]}");
        return sb.toString();
    }

    private void appendLevels(StringBuilder sb, TreeMap<Integer, Integer> book, int levels, double pips) {
        int count = 0;
        for (Map.Entry<Integer, Integer> entry : book.entrySet()) {
            if (count >= levels) break;
            if (count > 0) sb.append(',');
            sb.append(String.format("[%.6f,%d]", entry.getKey() * pips, entry.getValue()));
            count++;
        }
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
}
