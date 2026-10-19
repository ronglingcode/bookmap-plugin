package com.bookmap.plugin.rong;

import java.time.*;
import java.util.*;

/** Largest executed volume in a single side/price wall episode since the regular-session open. */
final class SessionOrderSizeTracker {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final long REMOVAL_GRACE_NS = 250_000_000L;
    private final TreeMap<Double, Wall> bids = new TreeMap<>(), offers = new TreeMap<>();
    private LocalDate date;
    private long lastTimestampNs;
    private boolean regular, started, seedFromSharedBook = true;
    private Double bestBid, bestAsk;
    private Observation largest;

    private static final class Wall {
        int size;
        long filled, removedAt;
        Wall(int size) { this.size = size; }
    }
    synchronized boolean advance(long timestampNs, OrderBookState book, double pips) {
        if (timestampNs <= 0) { regular = false; return false; }
        ZonedDateTime time = Instant.ofEpochSecond(timestampNs / 1_000_000_000L,
                timestampNs % 1_000_000_000L).atZone(NEW_YORK);
        if (lastTimestampNs > 0 && timestampNs < lastTimestampNs) {
            bids.clear(); offers.clear(); largest = null; bestBid = bestAsk = null;
            started = false; seedFromSharedBook = false;
        }
        lastTimestampNs = timestampNs;
        if (!time.toLocalDate().equals(date)) {
            date = time.toLocalDate(); largest = null; started = false; bestBid = bestAsk = null;
            // Resting quotes may persist overnight, but yesterday's executions cannot.
            for (TreeMap<Double, Wall> side : List.of(bids, offers)) {
                side.values().removeIf(wall -> wall.removedAt != 0);
                side.values().forEach(wall -> wall.filled = 0);
            }
        }
        LocalTime local = time.toLocalTime();
        regular = time.getDayOfWeek().getValue() <= 5 && !local.isBefore(LocalTime.of(9, 30))
                && local.isBefore(LocalTime.of(16, 0));
        for (TreeMap<Double, Wall> side : List.of(bids, offers))
            side.values().removeIf(wall -> wall.removedAt != 0 && timestampNs - wall.removedAt > REMOVAL_GRACE_NS);
        if (!regular) return false;
        if (!started) {
            started = true;
            if (seedFromSharedBook) {
                bids.clear(); offers.clear();
                for (boolean bid : new boolean[]{true, false})
                    for (Map.Entry<Integer, Integer> level : book.getLevelsSnapshot(bid).entrySet())
                        if (level.getKey() > 0 && level.getValue() > 0)
                            (bid ? bids : offers).put(BookmapPriceNormalizer.toWirePrice(level.getKey(), pips), new Wall(level.getValue()));
            }
        }
        return true;
    }
    synchronized void onDepth(boolean isBid, double price, int size, long timestampNs, OrderBookState book, double pips) {
        if (timestampNs <= 0) return;
        advance(timestampNs, book, pips);
        if (!Double.isFinite(price) || price <= 0 || size < 0) return;
        TreeMap<Double, Wall> side = isBid ? bids : offers;
        Wall wall = side.get(price);
        if (size == 0) {
            if (wall != null && wall.removedAt == 0) { wall.size = 0; wall.removedAt = timestampNs; }
        } else if (wall == null || wall.removedAt != 0) side.put(price, new Wall(size));
        else wall.size = size;
    }
    synchronized void onBbo(double bid, double ask, long timestampNs, OrderBookState book, double pips) {
        advance(timestampNs, book, pips);
        bestBid = bestAsk = null;
        if (Double.isFinite(bid) && Double.isFinite(ask) && bid > 0 && ask > bid) { bestBid = bid; bestAsk = ask; }
    }
    synchronized void onTrade(double price, int size, Boolean buyAggressor, long timestampNs,
            boolean ready, OrderBookState book, double pips) {
        if (!advance(timestampNs, book, pips) || !ready || !Double.isFinite(price) || price <= 0 || size <= 0) return;
        Boolean isBid = buyAggressor == null ? null : !buyAggressor;
        if (isBid == null && bestBid != null && bestAsk != null) {
            if (Double.compare(price, bestBid) == 0) isBid = true;
            else if (Double.compare(price, bestAsk) == 0) isBid = false;
        }
        if (isBid == null) return; // Unknown-side prints cannot prove execution against either wall.
        Wall wall = (isBid ? bids : offers).get(price);
        if (wall == null) return;
        wall.filled = Long.MAX_VALUE - wall.filled < size ? Long.MAX_VALUE : wall.filled + size;
        if (largest == null || wall.filled > largest.size)
            largest = new Observation(price, wall.filled, isBid, timestampNs);
    }
    synchronized Observation largestObservation() { return largest; }
    synchronized boolean available() { return regular; }
    synchronized String sessionDate() { return date == null ? "" : date.toString(); }

    static final class Observation {
        final double price;
        final long size;
        final boolean isBid;
        final long timestampNs;
        Observation(double price, long size, boolean isBid, long timestampNs) {
            this.price = price; this.size = size; this.isBid = isBid; this.timestampNs = timestampNs;
        }
    }
}
