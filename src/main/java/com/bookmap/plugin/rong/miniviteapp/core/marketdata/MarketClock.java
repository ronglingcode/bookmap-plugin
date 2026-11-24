package com.bookmap.plugin.rong.miniviteapp.core.marketdata;

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
            isPremarket = minutes < 570; isRegularSession = minutes >= 570 && (minutes < 960 || (minutes == 960 && value.getSecond() == 0 && value.getNano() == 0));
        }
    }
    public static Time marketTime(long epochMs) { return new Time(Instant.ofEpochMilli(epochMs).atZone(MARKET_ZONE)); }
    public static boolean isRegularTradingTime(long epochMs) {
        ZonedDateTime value = Instant.ofEpochMilli(epochMs).atZone(MARKET_ZONE);
        int minute = value.getHour() * 60 + value.getMinute();
        return value.getDayOfWeek().getValue() <= 5 && minute >= 570 && minute < 960;
    }
    public static boolean isExtendedTradingTime(long epochMs) {
        ZonedDateTime value = Instant.ofEpochMilli(epochMs).atZone(MARKET_ZONE);
        int minute = value.getHour() * 60 + value.getMinute();
        return value.getDayOfWeek().getValue() <= 5 && (minute >= 420 && minute < 570 || minute >= 960 && minute < 1200);
    }
    public static String addDays(String date, int days) { return LocalDate.parse(date).plusDays(days).toString(); }
}
