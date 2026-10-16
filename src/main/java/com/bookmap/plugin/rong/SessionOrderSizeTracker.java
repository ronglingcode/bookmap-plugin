package com.bookmap.plugin.rong;

import java.time.*;
import java.util.Map;
import java.util.TreeMap;

/** Session maxima by price, including liquidity subsequently removed from the book. */
final class SessionOrderSizeTracker {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private final TreeMap<Double, Integer> maxima = new TreeMap<>();
    private LocalDate date;
    private boolean started;

    synchronized boolean advance(long timestampNs, OrderBookState book, double pips) {
        if (timestampNs <= 0) return false;
        ZonedDateTime time = Instant.ofEpochSecond(timestampNs / 1_000_000_000L,
                timestampNs % 1_000_000_000L).atZone(NEW_YORK);
        if (date != null && time.toLocalDate().isBefore(date)) return false;
        if (!time.toLocalDate().equals(date)) {
            date = time.toLocalDate(); maxima.clear(); started = false;
        }
        if (time.toLocalTime().isBefore(LocalTime.of(9, 30))
                || !time.toLocalTime().isBefore(LocalTime.of(16, 0))) return false;
        if (!started) {
            started = true;
            // Resting premarket liquidity still present at the open is observed at the open.
            for (boolean bid : new boolean[]{true, false})
                for (Map.Entry<Integer, Integer> level : book.getLevelsSnapshot(bid).entrySet())
                    record(BookmapPriceNormalizer.toWirePrice(level.getKey(), pips), level.getValue());
        }
        return true;
    }

    synchronized void onDepth(double price, int size, long timestampNs, OrderBookState book, double pips) {
        if (advance(timestampNs, book, pips)) record(price, size);
    }

    private void record(double price, int size) {
        if (Double.isFinite(price) && price > 0 && size > 0) maxima.merge(price, size, Math::max);
    }

    synchronized int largest(String sessionDate, double low, double high) {
        if (date == null || !date.toString().equals(sessionDate) || !Double.isFinite(low)
                || !Double.isFinite(high) || low > high) return 0;
        return maxima.subMap(low - 1e-8, true, high + 1e-8, true).values().stream()
                .mapToInt(Integer::intValue).max().orElse(0);
    }
}
