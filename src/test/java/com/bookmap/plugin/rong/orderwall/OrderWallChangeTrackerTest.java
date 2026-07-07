package com.bookmap.plugin.rong.orderwall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class OrderWallChangeTrackerTest {

    private static final int LARGE_THRESHOLD = 5_000;

    @Test
    void skipsLargeOrderThatDisappearsBeforeLifetime() throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertSeen = new CountDownLatch(1);
        OrderWallChangeTracker tracker = newTracker(100, event -> {
            events.add(event);
            alertSeen.countDown();
        });

        try {
            tracker.markReady();
            tracker.onDepth(false, 19_400, 7_000, 1L);
            tracker.onDepth(false, 19_400, 0, 2L);

            assertFalse(alertSeen.await(300, TimeUnit.MILLISECONDS));
            assertTrue(events.isEmpty());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void alertsAddedOnlyAfterLargeOrderSurvivesLifetime() throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertSeen = new CountDownLatch(1);
        OrderWallChangeTracker tracker = newTracker(60, event -> {
            events.add(event);
            alertSeen.countDown();
        });

        try {
            tracker.markReady();
            tracker.onDepth(true, 11_020, 6_500, 10L);

            assertTrue(alertSeen.await(500, TimeUnit.MILLISECONDS));
            assertEquals(1, events.size());
            OrderWallChangeEvent event = events.get(0);
            assertEquals(OrderWallChangeEvent.Type.ADDED, event.getType());
            assertEquals(0, event.getPreviousSize());
            assertEquals(6_500, event.getCurrentSize());
            assertTrue(event.isBid());
            assertEquals(11_020, event.getPriceTick());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void alertsWhenExistingLargeOrderDoublesAndSurvivesLifetime() throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertSeen = new CountDownLatch(1);
        OrderWallChangeTracker tracker = newTracker(60, event -> {
            events.add(event);
            alertSeen.countDown();
        });

        try {
            tracker.onDepth(true, 12_700, 11_000, 1L);
            tracker.markReady();
            tracker.onDepth(true, 12_700, 58_000, 2L);

            assertTrue(alertSeen.await(500, TimeUnit.MILLISECONDS));
            assertEquals(1, events.size());
            OrderWallChangeEvent event = events.get(0);
            assertEquals(OrderWallChangeEvent.Type.INCREASED, event.getType());
            assertEquals(11_000, event.getPreviousSize());
            assertEquals(58_000, event.getCurrentSize());
            assertTrue(event.isBid());
            assertEquals(12_700, event.getPriceTick());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void skipsDoubledIncreaseWhenNewSizeDoesNotExceedLargeThreshold() throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertSeen = new CountDownLatch(1);
        OrderWallChangeTracker tracker = newTracker(60, event -> {
            events.add(event);
            alertSeen.countDown();
        });

        try {
            tracker.markReady();
            tracker.onDepth(true, 11_020, 2_000, 1L);
            tracker.onDepth(true, 11_020, 4_000, 2L);

            assertFalse(alertSeen.await(200, TimeUnit.MILLISECONDS));
            assertTrue(events.isEmpty());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void suppressedFlashIncreaseDoesNotAlertAsDecreaseWhenItReturnsToOriginalSize() throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertSeen = new CountDownLatch(1);
        OrderWallChangeTracker tracker = newTracker(100, event -> {
            events.add(event);
            alertSeen.countDown();
        });

        try {
            tracker.onDepth(true, 12_700, 11_000, 1L);
            tracker.markReady();
            tracker.onDepth(true, 12_700, 58_000, 2L);
            tracker.onDepth(true, 12_700, 11_000, 3L);

            assertFalse(alertSeen.await(300, TimeUnit.MILLISECONDS));
            assertTrue(events.isEmpty());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void existingLargeSnapshotAtReadyCanAlertOnPullWithoutWaitingLifetime() throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertSeen = new CountDownLatch(1);
        OrderWallChangeTracker tracker = newTracker(1_000, event -> {
            events.add(event);
            alertSeen.countDown();
        });

        try {
            tracker.onDepth(true, 11_020, 8_000, 1L);
            tracker.markReady();
            tracker.onDepth(true, 11_020, 1_000, 2L);

            assertTrue(alertSeen.await(500, TimeUnit.MILLISECONDS));
            assertEquals(1, events.size());
            OrderWallChangeEvent event = events.get(0);
            assertEquals(OrderWallChangeEvent.Type.REDUCED, event.getType());
            assertEquals(8_000, event.getPreviousSize());
            assertEquals(1_000, event.getCurrentSize());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void suppressesNearZeroReductionWhenSamePriceTradesShowWallWasFilled() throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertSeen = new CountDownLatch(1);
        OrderWallChangeTracker tracker = newTracker(1_000, event -> {
            events.add(event);
            alertSeen.countDown();
        });

        try {
            tracker.onDepth(false, 9_730, 6_000, 1L);
            tracker.markReady();

            // Even if the aggressor side does not match the ask-side wall, same-price
            // trades mean price touched this level and the depth drop is likely a fill.
            tracker.onTrade(9_730, 700, false);
            tracker.onDepth(false, 9_730, 102, 2L);

            assertFalse(alertSeen.await(500, TimeUnit.MILLISECONDS));
            assertTrue(events.isEmpty());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void alertsBidBreakdownWhenEnabledBidWallIsFilledAndCleared() throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertSeen = new CountDownLatch(1);
        OrderWallChangeTracker tracker = newWallBreakTracker(1_000, bidWall -> bidWall, event -> {
            events.add(event);
            alertSeen.countDown();
        });

        try {
            tracker.onDepth(true, 11_020, 8_000, 1L);
            tracker.markReady();
            tracker.onTrade(11_020, 8_000, false);
            tracker.onDepth(true, 11_020, 0, 2L);

            assertTrue(alertSeen.await(500, TimeUnit.MILLISECONDS));
            assertEquals(1, events.size());
            OrderWallChangeEvent event = events.get(0);
            assertEquals(OrderWallChangeEvent.Type.BID_BREAKDOWN, event.getType());
            assertTrue(event.isBid());
            assertEquals(8_000, event.getPreviousSize());
            assertEquals(0, event.getCurrentSize());
            assertEquals(8_000, event.getTradedSize());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void alertsOfferBreakoutWhenEnabledOfferWallIsFilledAndCleared() throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertSeen = new CountDownLatch(1);
        OrderWallChangeTracker tracker = newWallBreakTracker(1_000, bidWall -> !bidWall, event -> {
            events.add(event);
            alertSeen.countDown();
        });

        try {
            tracker.onDepth(false, 11_050, 9_000, 1L);
            tracker.markReady();
            tracker.onTrade(11_050, 9_000, true);
            tracker.onDepth(false, 11_050, 0, 2L);

            assertTrue(alertSeen.await(500, TimeUnit.MILLISECONDS));
            assertEquals(1, events.size());
            OrderWallChangeEvent event = events.get(0);
            assertEquals(OrderWallChangeEvent.Type.OFFER_BREAKOUT, event.getType());
            assertFalse(event.isBid());
            assertEquals(9_000, event.getPreviousSize());
            assertEquals(0, event.getCurrentSize());
            assertEquals(9_000, event.getTradedSize());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void suppressesFilledWallBreakWhenMatchingTradeButtonIsDisabled() throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertSeen = new CountDownLatch(1);
        OrderWallChangeTracker tracker = newWallBreakTracker(1_000, bidWall -> false, event -> {
            events.add(event);
            alertSeen.countDown();
        });

        try {
            tracker.onDepth(true, 11_020, 8_000, 1L);
            tracker.markReady();
            tracker.onTrade(11_020, 8_000, false);
            tracker.onDepth(true, 11_020, 0, 2L);

            assertFalse(alertSeen.await(500, TimeUnit.MILLISECONDS));
            assertTrue(events.isEmpty());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void cancelledWallClearDoesNotAlertAsWallBreak() throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertSeen = new CountDownLatch(1);
        OrderWallChangeTracker tracker = newWallBreakTracker(1_000, bidWall -> true, event -> {
            events.add(event);
            alertSeen.countDown();
        });

        try {
            tracker.onDepth(true, 11_020, 8_000, 1L);
            tracker.markReady();
            tracker.onDepth(true, 11_020, 0, 2L);

            assertTrue(alertSeen.await(500, TimeUnit.MILLISECONDS));
            assertEquals(1, events.size());
            assertEquals(OrderWallChangeEvent.Type.REDUCED, events.get(0).getType());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void adaptivePercentileSuppressesFiveThousandCrossWhenBookIsCrowded() throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertSeen = new CountDownLatch(1);
        OrderWallChangeTracker tracker = newAdaptiveTracker(60, event -> {
            events.add(event);
            alertSeen.countDown();
        });

        try {
            tracker.onDepth(false, 19_400, 200_000, 1L);
            tracker.onDepth(false, 19_410, 200_000, 2L);
            tracker.onDepth(false, 19_420, 200_000, 3L);
            tracker.markReady();

            tracker.onDepth(false, 19_430, 6_000, 4L);

            assertFalse(alertSeen.await(300, TimeUnit.MILLISECONDS));
            assertTrue(events.isEmpty());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void adaptivePercentileAlertsWhenOrderCrossesEffectiveThreshold() throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertSeen = new CountDownLatch(1);
        OrderWallChangeTracker tracker = newAdaptiveTracker(60, event -> {
            events.add(event);
            alertSeen.countDown();
        });

        try {
            tracker.onDepth(false, 19_400, 200_000, 1L);
            tracker.onDepth(false, 19_410, 200_000, 2L);
            tracker.onDepth(false, 19_420, 200_000, 3L);
            tracker.markReady();

            tracker.onDepth(false, 19_430, 200_000, 4L);

            assertTrue(alertSeen.await(500, TimeUnit.MILLISECONDS));
            assertEquals(1, events.size());
            OrderWallChangeEvent event = events.get(0);
            assertEquals(OrderWallChangeEvent.Type.ADDED, event.getType());
            assertEquals(0, event.getPreviousSize());
            assertEquals(200_000, event.getCurrentSize());
            assertEquals(19_430, event.getPriceTick());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void thresholdSupplierUpdatesFutureDepthDecisions() throws Exception {
        AtomicInteger thresholdFloor = new AtomicInteger(LARGE_THRESHOLD);
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertSeen = new CountDownLatch(1);
        OrderWallChangeTracker tracker = new OrderWallChangeTracker(
                "TEST",
                0.01,
                thresholdFloor::get,
                0,
                0.50,
                20,
                60,
                event -> {
                    events.add(event);
                    alertSeen.countDown();
                },
                bidWall -> false);

        try {
            tracker.markReady();
            thresholdFloor.set(10_000);

            tracker.onDepth(true, 12_700, 7_000, 1L);
            assertFalse(alertSeen.await(200, TimeUnit.MILLISECONDS));
            assertTrue(events.isEmpty());

            tracker.onDepth(true, 12_710, 11_000, 2L);
            assertTrue(alertSeen.await(500, TimeUnit.MILLISECONDS));
            assertEquals(1, events.size());
            assertEquals(OrderWallChangeEvent.Type.ADDED, events.get(0).getType());
            assertEquals(11_000, events.get(0).getCurrentSize());
        } finally {
            tracker.shutdown();
        }
    }

    private static OrderWallChangeTracker newTracker(long minLargeOrderLifetimeMs,
                                                     java.util.function.Consumer<OrderWallChangeEvent> consumer) {
        return new OrderWallChangeTracker(
                "TEST",
                0.01,
                LARGE_THRESHOLD,
                0.50,
                20,
                minLargeOrderLifetimeMs,
                consumer);
    }

    private static OrderWallChangeTracker newAdaptiveTracker(
            long minLargeOrderLifetimeMs,
            java.util.function.Consumer<OrderWallChangeEvent> consumer) {
        return new OrderWallChangeTracker(
                "TEST",
                0.01,
                LARGE_THRESHOLD,
                90,
                0.50,
                20,
                minLargeOrderLifetimeMs,
                consumer);
    }

    private static OrderWallChangeTracker newWallBreakTracker(
            long minLargeOrderLifetimeMs,
            java.util.function.Predicate<Boolean> wallBreakAlertEnabled,
            java.util.function.Consumer<OrderWallChangeEvent> consumer) {
        return new OrderWallChangeTracker(
                "TEST",
                0.01,
                LARGE_THRESHOLD,
                0.50,
                20,
                minLargeOrderLifetimeMs,
                consumer,
                wallBreakAlertEnabled);
    }
}
