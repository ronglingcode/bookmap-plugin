package com.bookmap.plugin.rong.orderwall;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

final class OrderWallAlertDisplayTiming {

    static final long PRE_MARKET_ALERT_TTL_MS = 10_000;
    static final long REGULAR_ALERT_TTL_MS = 30_000;

    private static final ZoneId PACIFIC_TIME = ZoneId.of("America/Los_Angeles");
    private static final LocalTime MARKET_OPEN_PACIFIC = LocalTime.of(6, 30);

    private OrderWallAlertDisplayTiming() {}

    static long maxTtlMs() {
        return Math.max(PRE_MARKET_ALERT_TTL_MS, REGULAR_ALERT_TTL_MS);
    }

    static long ttlMs(OrderWallChangeEvent event) {
        return isBeforeMarketOpenPacific(event.getCreatedAtMs())
                ? PRE_MARKET_ALERT_TTL_MS
                : REGULAR_ALERT_TTL_MS;
    }

    static boolean isVisible(OrderWallChangeEvent event, long nowMs) {
        return ageMs(event, nowMs) <= ttlMs(event);
    }

    static long ageMs(OrderWallChangeEvent event, long nowMs) {
        return Math.max(0, nowMs - event.getCreatedAtMs());
    }

    private static boolean isBeforeMarketOpenPacific(long epochMs) {
        LocalTime eventTime = Instant.ofEpochMilli(epochMs)
                .atZone(PACIFIC_TIME)
                .toLocalTime();
        return eventTime.isBefore(MARKET_OPEN_PACIFIC);
    }
}
