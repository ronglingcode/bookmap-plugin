package com.bookmap.plugin.rong;

import java.time.*;
import java.util.Map;
import java.util.TreeMap;

/** Session maxima by price, including liquidity subsequently removed from the book. */
final class SessionOrderSizeTracker {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private final TreeMap<Double, Observation> maxima = new TreeMap<>();
    private LocalDate date;
    private boolean started;
    private long lastTimestampNs;
    private boolean seedFromSharedBook = true;
    private final TreeMap<Double, Integer> freshBids = new TreeMap<>(), freshAsks = new TreeMap<>();

    synchronized boolean advance(long timestampNs, OrderBookState book, double pips) {
        if (timestampNs <= 0) return false;
        ZonedDateTime time = Instant.ofEpochSecond(timestampNs / 1_000_000_000L,
                timestampNs % 1_000_000_000L).atZone(NEW_YORK);
        if (lastTimestampNs > 0 && timestampNs < lastTimestampNs) {
            maxima.clear(); freshBids.clear(); freshAsks.clear(); started = false;
            // The shared book can still contain levels from the future after a replay seek.
            seedFromSharedBook = false;
        }
        lastTimestampNs = timestampNs;
        if (!time.toLocalDate().equals(date)) {
            date = time.toLocalDate(); maxima.clear(); started = false;
        }
        if (time.toLocalTime().isBefore(LocalTime.of(9, 30))
                || !time.toLocalTime().isBefore(LocalTime.of(16, 0))) return false;
        if (!started) {
            started = true;
            // Resting premarket liquidity still present at the open is observed at the open.
            if (seedFromSharedBook) {
                for (boolean bid : new boolean[]{true, false})
                    for (Map.Entry<Integer, Integer> level : book.getLevelsSnapshot(bid).entrySet())
                        if (level.getKey() > 0)
                            record(BookmapPriceNormalizer.toWirePrice(level.getKey(), pips), level.getValue(), bid, timestampNs);
            } else {
                freshBids.forEach((price, size) -> record(price, size, true, timestampNs));
                freshAsks.forEach((price, size) -> record(price, size, false, timestampNs));
            }
        }
        return true;
    }

    synchronized void onDepth(boolean isBid, double price, int size, long timestampNs, OrderBookState book, double pips) {
        if (timestampNs <= 0) return;
        boolean regular = advance(timestampNs, book, pips);
        if (Double.isFinite(price) && price > 0 && size >= 0) {
            TreeMap<Double, Integer> fresh = isBid ? freshBids : freshAsks;
            if (size == 0) fresh.remove(price); else fresh.put(price, size);
        }
        if (regular) record(price, size, isBid, timestampNs);
    }

    private void record(double price, int size, boolean isBid, long timestampNs) {
        if (!Double.isFinite(price) || price <= 0 || size <= 0) return;
        Observation previous = maxima.get(price);
        if (previous == null || size > previous.size) maxima.put(price, new Observation(price, size, isBid, timestampNs));
    }

    synchronized int largest(String sessionDate, double low, double high) {
        Observation value = largestObservation(sessionDate, low, high);
        return value == null ? 0 : value.size;
    }

    synchronized Observation largestObservation(String sessionDate, double low, double high) {
        if (date == null || !date.toString().equals(sessionDate) || !Double.isFinite(low)
                || !Double.isFinite(high) || low > high) return null;
        return maxima.subMap(low - 1e-8, true, high + 1e-8, true).values().stream()
                .max(java.util.Comparator.comparingInt(value -> value.size)).orElse(null);
    }

    static final class Observation {
        final double price;
        final int size;
        final boolean isBid;
        final long timestampNs;
        Observation(double price, int size, boolean isBid, long timestampNs) {
            this.price = price; this.size = size; this.isBid = isBid; this.timestampNs = timestampNs;
        }
    }
}
