package com.bookmap.plugin.rong.orderwall;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class OrderWallChangePainterTest {

    @Test
    void breakoutSignalsStayHiddenUnlessTheirDedicatedFlagIsEnabled() {
        long nowMs = System.currentTimeMillis();
        OrderWallChangeEvent wallChange = event(OrderWallChangeEvent.Type.ADDED, nowMs);
        OrderWallChangeEvent offerBreakout = event(OrderWallChangeEvent.Type.OFFER_BREAKOUT, nowMs);
        List<OrderWallChangeEvent> events = Arrays.asList(wallChange, offerBreakout);

        assertEquals(
                Arrays.asList(wallChange),
                OrderWallChangePainter.visibleEvents(events, nowMs, true, false));
        assertEquals(
                Arrays.asList(offerBreakout),
                OrderWallChangePainter.visibleEvents(events, nowMs, false, true));
        assertEquals(
                events,
                OrderWallChangePainter.visibleEvents(events, nowMs, true, true));
    }

    private static OrderWallChangeEvent event(OrderWallChangeEvent.Type type, long createdAtMs) {
        return new OrderWallChangeEvent(
                "TEST",
                type == OrderWallChangeEvent.Type.BID_BREAKDOWN,
                10_000,
                100.0,
                9_000,
                0,
                9_000,
                type,
                1L,
                createdAtMs);
    }
}
