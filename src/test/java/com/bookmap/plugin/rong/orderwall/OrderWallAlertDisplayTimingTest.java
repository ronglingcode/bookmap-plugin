package com.bookmap.plugin.rong.orderwall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class OrderWallAlertDisplayTimingTest {

    private static final ZoneId PACIFIC_TIME = ZoneId.of("America/Los_Angeles");

    @Test
    void beforePacificMarketOpenAlertsExpireAfterTenSeconds() {
        OrderWallChangeEvent event = eventAt(LocalTime.of(6, 29, 59));
        long createdAtMs = event.getCreatedAtMs();

        assertEquals(10_000, OrderWallAlertDisplayTiming.ttlMs(event));
        assertTrue(OrderWallAlertDisplayTiming.isVisible(event, createdAtMs + 10_000));
        assertFalse(OrderWallAlertDisplayTiming.isVisible(event, createdAtMs + 10_001));
    }

    @Test
    void atPacificMarketOpenAlertsKeepRegularThirtySecondTtl() {
        OrderWallChangeEvent event = eventAt(LocalTime.of(6, 30));
        long createdAtMs = event.getCreatedAtMs();

        assertEquals(30_000, OrderWallAlertDisplayTiming.ttlMs(event));
        assertTrue(OrderWallAlertDisplayTiming.isVisible(event, createdAtMs + 10_001));
        assertTrue(OrderWallAlertDisplayTiming.isVisible(event, createdAtMs + 30_000));
        assertFalse(OrderWallAlertDisplayTiming.isVisible(event, createdAtMs + 30_001));
    }

    private static OrderWallChangeEvent eventAt(LocalTime time) {
        long createdAtMs = LocalDateTime.of(LocalDate.of(2026, 6, 29), time)
                .atZone(PACIFIC_TIME)
                .toInstant()
                .toEpochMilli();
        return new OrderWallChangeEvent(
                "TEST",
                true,
                10_000,
                100.0,
                8_000,
                0,
                8_000,
                OrderWallChangeEvent.Type.BID_BREAKDOWN,
                1L,
                createdAtMs);
    }
}
