package com.bookmap.plugin.rong.miniviteapp.runtime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** All domain session calculations use Eastern Time, independently of host timezone. */
public final class MarketClock {
    private static final ZoneId MARKET_ZONE = ZoneId.of("America/New_York");
    private MarketClock() {}
    public static final class Time {
        public final String date;
        public final double minutesSinceMarketOpen;
        public final boolean isPremarket, isRegularSession;
        private Time(ZonedDateTime value) {
            date = value.toLocalDate().toString();
            int minutes = value.getHour() * 60 + value.getMinute();
            minutesSinceMarketOpen = minutes - 570 + value.getSecond() / 60.0 + value.getNano() / 60_000_000_000.0;
            isPremarket = minutes < 570; isRegularSession = minutes >= 570 && minutes < 960;
        }
    }
    public static Time marketTime(long epochMs) { return new Time(Instant.ofEpochMilli(epochMs).atZone(MARKET_ZONE)); }
    public static String addDays(String date, int days) { return LocalDate.parse(date).plusDays(days).toString(); }
}
