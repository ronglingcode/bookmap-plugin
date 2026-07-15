package com.bookmap.plugin.rong;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import com.google.gson.JsonObject;

final class RegularSessionHighLowTracker {

    private static final long NS_PER_SECOND = 1_000_000_000L;
    private static final ZoneId NEW_YORK_TIME = ZoneId.of("America/New_York");
    private static final LocalTime MARKET_OPEN_NEW_YORK = LocalTime.of(9, 30);

    private LocalDate sessionDate;
    private double high = Double.NaN;
    private double low = Double.NaN;
    private long updatedAtMs;

    synchronized void onTrade(double price, long timestampNs) {
        if (!Double.isFinite(price) || price <= 0) {
            return;
        }

        ZonedDateTime eventTime = toInstant(timestampNs).atZone(NEW_YORK_TIME);
        LocalDate eventDate = eventTime.toLocalDate();
        if (sessionDate == null || !sessionDate.equals(eventDate)) {
            reset(eventDate);
        }

        if (eventTime.toLocalTime().isBefore(MARKET_OPEN_NEW_YORK)) {
            return;
        }

        if (!Double.isFinite(high) || price > high) {
            high = price;
        }
        if (!Double.isFinite(low) || price < low) {
            low = price;
        }
        updatedAtMs = eventTime.toInstant().toEpochMilli();
    }

    synchronized Snapshot snapshot() {
        if (!Double.isFinite(high) || !Double.isFinite(low)) {
            return null;
        }
        return new Snapshot(sessionDate.toString(), high, low, updatedAtMs);
    }

    private void reset(LocalDate newSessionDate) {
        sessionDate = newSessionDate;
        high = Double.NaN;
        low = Double.NaN;
        updatedAtMs = 0L;
    }

    private static Instant toInstant(long timestampNs) {
        if (timestampNs <= 0) {
            return Instant.now();
        }
        long seconds = Math.floorDiv(timestampNs, NS_PER_SECOND);
        long nanos = Math.floorMod(timestampNs, NS_PER_SECOND);
        return Instant.ofEpochSecond(seconds, nanos);
    }

    static final class Snapshot {
        private final String sessionDate;
        private final double high;
        private final double low;
        private final long updatedAtMs;

        private Snapshot(String sessionDate, double high, double low, long updatedAtMs) {
            this.sessionDate = sessionDate;
            this.high = high;
            this.low = low;
            this.updatedAtMs = updatedAtMs;
        }

        double getHigh() {
            return high;
        }

        double getLow() {
            return low;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("source", "bookmap");
            json.addProperty("sessionDate", sessionDate);
            json.addProperty("high", high);
            json.addProperty("low", low);
            json.addProperty("timestamp", updatedAtMs);
            return json;
        }
    }
}
