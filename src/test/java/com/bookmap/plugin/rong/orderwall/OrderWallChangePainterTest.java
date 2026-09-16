package com.bookmap.plugin.rong.orderwall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class OrderWallChangePainterTest {

    @Test
    void onlyActiveNonTradeInRangeChangesAreVisible() {
        long nowMs = System.currentTimeMillis();
        OrderWallChangeEvent active = filteredEvent(nowMs, false, true);
        OrderWallChangeEvent tradeConsumed = filteredEvent(nowMs, true, true);
        OrderWallChangeEvent outsideRange = filteredEvent(nowMs, false, false);
        List<OrderWallChangeEvent> events = Arrays.asList(active, tradeConsumed, outsideRange);

        assertEquals(
                Arrays.asList(active),
                OrderWallChangePainter.visibleEvents(events, nowMs, true));
        assertEquals(
                Arrays.asList(),
                OrderWallChangePainter.visibleEvents(events, nowMs, false));
    }

    @Test
    void labelColorsMatchDirectionalMeaning() {
        long nowMs = System.currentTimeMillis();
        Color green = new Color(60, 220, 148);
        Color red = new Color(255, 91, 78);

        assertEquals(green, OrderWallChangePainter.colorFor(directionalEvent(nowMs, true, true)));
        assertEquals(red, OrderWallChangePainter.colorFor(directionalEvent(nowMs, true, false)));
        assertEquals(red, OrderWallChangePainter.colorFor(directionalEvent(nowMs, false, true)));
        assertEquals(green, OrderWallChangePainter.colorFor(directionalEvent(nowMs, false, false)));
    }

    @Test
    void offerLabelsSitAbovePriceAndBidLabelsSitBelowPrice() {
        long nowMs = System.currentTimeMillis();
        OrderWallChangeEvent offer = directionalEvent(nowMs, false, true);
        OrderWallChangeEvent bid = directionalEvent(nowMs, true, true);

        assertTrue(OrderWallChangePainter.badgeBottomOffset(offer) < 0);
        assertTrue(OrderWallChangePainter.badgeTopOffset(bid) > 0);
        assertEquals("OFFER DOWN", OrderWallChangePainter.eventBadgeText(offer));
    }

    private static OrderWallChangeEvent filteredEvent(long createdAtMs, boolean tradeConsumption,
                                                       boolean withinDayRange) {
        return event(createdAtMs, true, true, tradeConsumption, withinDayRange);
    }

    private static OrderWallChangeEvent directionalEvent(long createdAtMs, boolean bid, boolean increase) {
        return event(createdAtMs, bid, increase, false, true);
    }

    private static OrderWallChangeEvent event(long createdAtMs, boolean bid, boolean increase,
                                               boolean tradeConsumption, boolean withinDayRange) {
        return new OrderWallChangeEvent(
                "TEST",
                bid,
                10_000,
                100.0,
                increase ? 9_000 : 15_000,
                increase ? 15_000 : 9_000,
                0,
                increase ? OrderWallChangeEvent.Type.INCREASED : OrderWallChangeEvent.Type.REPLACED_SMALLER,
                1L,
                createdAtMs,
                5_000,
                false,
                true,
                tradeConsumption,
                withinDayRange,
                110.0,
                90.0);
    }
}
