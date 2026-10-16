package com.bookmap.plugin.rong;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import com.google.gson.JsonObject;

final class RegularSessionHighLowTracker {

    private static final long NS_PER_SECOND = 1_000_000_000L;
    private static final ZoneId NEW_YORK_TIME = ZoneId.of("America/New_York");
    private static final LocalTime MARKET_OPEN_NEW_YORK = LocalTime.of(9, 30);
    private static final LocalTime MARKET_CLOSE_NEW_YORK = LocalTime.of(16, 0);

    private LocalDate sessionDate;
    private double highOfDay = Double.NaN;
    private double lowOfDay = Double.NaN;
    private double openPrice = Double.NaN;
    private boolean hasMinuteHistory;
    private long updatedAtMs;
    private long chartTimeNs;
    private final TreeMap<Long, Candle> minuteHistory = new TreeMap<>();
    private long appliedHistoryThroughMs = Long.MIN_VALUE;

    synchronized boolean isChartDriven() { return chartTimeNs > 0; }

    /** Chart time, rather than the live feed's clock, bounds replay history. */
    synchronized void onTimestamp(long timestampNs) {
        if (timestampNs <= 0) return;
        LocalDate date = toInstant(timestampNs).atZone(NEW_YORK_TIME).toLocalDate();
        if (chartTimeNs == 0 || timestampNs < chartTimeNs || !date.equals(sessionDate)) reset(date);
        chartTimeNs = timestampNs;
        applyAvailableHistory();
    }

    private void applyAvailableHistory() {
        long cutoff = chartTimeNs / 1_000_000L;
        Map.Entry<Long, Candle> next;
        while ((next = minuteHistory.higherEntry(appliedHistoryThroughMs)) != null) {
            Candle bar = next.getValue();
            // A current minute's final high/low would reveal up to 59 seconds of future prices.
            if (bar.datetime + 60_000 > cutoff) break;
            applyBar(bar); appliedHistoryThroughMs = bar.datetime;
        }
        // The opening print is already known at 09:30, even while its minute is unfinished.
        Map.Entry<Long, Candle> first = minuteHistory.firstEntry();
        if (first != null && first.getKey() <= cutoff) {
            Candle bar = first.getValue();
            if (Instant.ofEpochMilli(bar.datetime).atZone(NEW_YORK_TIME).toLocalTime().equals(MARKET_OPEN_NEW_YORK)
                    && Double.isFinite(bar.open) && bar.open > 0) openPrice = bar.open;
        }
    }

    synchronized void onTrade(double price, long timestampNs) {
        if (!Double.isFinite(price) || price <= 0) {
            return;
        }

        ZonedDateTime eventTime = toInstant(timestampNs).atZone(NEW_YORK_TIME);
        LocalDate eventDate = eventTime.toLocalDate();
        if (sessionDate != null && eventDate.isBefore(sessionDate)) {
            return;
        }
        if (sessionDate == null || eventDate.isAfter(sessionDate)) {
            reset(eventDate);
        }

        if (!isRegularSession(eventTime.toLocalTime())) {
            return;
        }

        if (!Double.isFinite(highOfDay) || price > highOfDay) {
            highOfDay = price;
        }
        if (!Double.isFinite(lowOfDay) || price < lowOfDay) {
            lowOfDay = price;
        }
        updatedAtMs = eventTime.toInstant().toEpochMilli();
    }

    synchronized void onMinuteBars(String date, List<Candle> bars) {
        LocalDate historyDate = LocalDate.parse(date);
        if (chartTimeNs > 0 && !historyDate.equals(sessionDate)) return;
        if (sessionDate != null && historyDate.isBefore(sessionDate)) {
            return;
        }
        if (sessionDate == null || historyDate.isAfter(sessionDate)) {
            reset(historyDate);
        }
        for (Candle bar : bars) {
            ZonedDateTime time = Instant.ofEpochMilli(bar.datetime).atZone(NEW_YORK_TIME);
            if (!time.toLocalDate().equals(historyDate) || !isRegularSession(time.toLocalTime())
                    || !Double.isFinite(bar.high) || !Double.isFinite(bar.low)
                    || bar.high <= 0 || bar.low <= 0 || bar.high < bar.low) {
                continue;
            }
            minuteHistory.put(bar.datetime, bar);
            if (chartTimeNs == 0 || bar.datetime + 60_000 <= chartTimeNs / 1_000_000L) applyBar(bar);
        }
        if (chartTimeNs > 0) applyAvailableHistory();
    }

    private void applyBar(Candle bar) {
        highOfDay = Double.isFinite(highOfDay) ? Math.max(highOfDay, bar.high) : bar.high;
        lowOfDay = Double.isFinite(lowOfDay) ? Math.min(lowOfDay, bar.low) : bar.low;
        hasMinuteHistory = true;
        if (Instant.ofEpochMilli(bar.datetime).atZone(NEW_YORK_TIME).toLocalTime().equals(MARKET_OPEN_NEW_YORK)
                && Double.isFinite(bar.open) && bar.open > 0) openPrice = bar.open;
        updatedAtMs = Math.max(updatedAtMs, bar.datetime);
    }

    private static boolean isRegularSession(LocalTime time) {
        return !time.isBefore(MARKET_OPEN_NEW_YORK) && time.isBefore(MARKET_CLOSE_NEW_YORK);
    }

    synchronized Snapshot snapshot() {
        if (!Double.isFinite(highOfDay) || !Double.isFinite(lowOfDay)) {
            return null;
        }
        return new Snapshot(sessionDate.toString(), openPrice, highOfDay, lowOfDay, updatedAtMs, hasMinuteHistory);
    }

    static Snapshot fromMarketState(JsonObject json) {
        if (json == null || !json.has("sessionDate") || !json.has("highOfDay") || !json.has("lowOfDay")) return null;
        try {
            String date = json.get("sessionDate").getAsString();
            double highOfDay = json.get("highOfDay").getAsDouble();
            double lowOfDay = json.get("lowOfDay").getAsDouble();
            if (!Double.isFinite(highOfDay) || !Double.isFinite(lowOfDay)
                    || highOfDay <= 0 || lowOfDay <= 0 || highOfDay < lowOfDay) return null;
            double openPrice = json.has("openPrice") ? json.get("openPrice").getAsDouble() : Double.NaN;
            if (!Double.isFinite(openPrice) || openPrice <= 0) openPrice = Double.NaN;
            long timestamp = json.has("timestamp") ? json.get("timestamp").getAsLong() : 0;
            return new Snapshot(date, openPrice, highOfDay, lowOfDay, timestamp, true);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private void reset(LocalDate newSessionDate) {
        if (!newSessionDate.equals(sessionDate)) minuteHistory.clear();
        appliedHistoryThroughMs = Long.MIN_VALUE;
        sessionDate = newSessionDate;
        highOfDay = Double.NaN;
        lowOfDay = Double.NaN;
        openPrice = Double.NaN;
        hasMinuteHistory = false;
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
        private final double openPrice;
        private final double highOfDay;
        private final double lowOfDay;
        private final long updatedAtMs;
        private final boolean hasMinuteHistory;

        private Snapshot(String sessionDate, double openPrice, double highOfDay, double lowOfDay,
                         long updatedAtMs, boolean hasMinuteHistory) {
            this.sessionDate = sessionDate;
            this.openPrice = openPrice;
            this.highOfDay = highOfDay;
            this.lowOfDay = lowOfDay;
            this.updatedAtMs = updatedAtMs;
            this.hasMinuteHistory = hasMinuteHistory;
        }

        double getHighOfDay() {
            return highOfDay;
        }

        double getLowOfDay() {
            return lowOfDay;
        }

        double getOpenPrice() {
            return openPrice;
        }

        String getSessionDate() {
            return sessionDate;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("source", hasMinuteHistory ? "bookmap+massive" : "bookmap");
            BookmapPriceNormalizer.addWirePriceUnit(json);
            json.addProperty("sessionDate", sessionDate);
            json.addProperty("highOfDay", highOfDay);
            json.addProperty("lowOfDay", lowOfDay);
            if (Double.isFinite(openPrice)) json.addProperty("openPrice", openPrice);
            json.addProperty("timestamp", updatedAtMs);
            return json;
        }
    }
}
