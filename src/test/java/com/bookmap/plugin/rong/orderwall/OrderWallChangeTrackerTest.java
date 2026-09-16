package com.bookmap.plugin.rong.orderwall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class OrderWallChangeTrackerTest {

    private static final int LARGE_THRESHOLD = 5_000;
    private static final long TEST_SURVIVAL_MS = 60;

    @Test
    void skipsChangeThatDoesNotSurviveStabilityWindow() throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertSeen = new CountDownLatch(1);
        OrderWallChangeTracker tracker = newTracker(TEST_SURVIVAL_MS, event -> {
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
    void upwardThresholdCrossEmitsAfterSurvivalWindow() throws Exception {
        OrderWallChangeEvent event = captureSingleEvent(tracker -> {
            tracker.markReady();
            tracker.onDepth(true, 11_020, 6_500, 10L);
        });

        assertEquals(OrderWallChangeEvent.Type.ADDED, event.getType());
        assertEquals(0, event.getPreviousSize());
        assertEquals(6_500, event.getCurrentSize());
        assertEquals(LARGE_THRESHOLD, event.getEffectiveThreshold());
        assertTrue(event.isCrossedThreshold());
        assertTrue(event.isDeltaExceedsThreshold());
        assertEquals(OrderWallChangeEvent.LabelType.BID_UP, event.getLabelType());
    }

    @Test
    void downwardThresholdCrossIsMaterialEvenWhenDeltaIsBelowThreshold() throws Exception {
        OrderWallChangeEvent event = captureSingleEvent(tracker -> {
            tracker.onDepth(true, 11_020, 6_000, 1L);
            tracker.markReady();
            tracker.onDepth(true, 11_020, 4_000, 2L);
        });

        assertTrue(event.isCrossedThreshold());
        assertFalse(event.isDeltaExceedsThreshold());
        assertEquals(OrderWallChangeEvent.Type.REDUCED, event.getType());
        assertEquals(OrderWallChangeEvent.LabelType.BID_PULL, event.getLabelType());
    }

    @Test
    void increaseAboveThresholdAlertsWhenAbsoluteDeltaExceedsThreshold() throws Exception {
        OrderWallChangeEvent event = captureSingleEvent(tracker -> {
            tracker.onDepth(true, 12_700, 10_000, 1L);
            tracker.markReady();
            tracker.onDepth(true, 12_700, 18_000, 2L);
        });

        assertFalse(event.isCrossedThreshold());
        assertTrue(event.isDeltaExceedsThreshold());
        assertEquals(8_000, event.getSizeDelta());
        assertEquals(OrderWallChangeEvent.Type.INCREASED, event.getType());
    }

    @Test
    void decreaseAboveThresholdAlertsWhenAbsoluteDeltaExceedsThreshold() throws Exception {
        OrderWallChangeEvent event = captureSingleEvent(tracker -> {
            tracker.onDepth(true, 12_700, 25_000, 1L);
            tracker.markReady();
            tracker.onDepth(true, 12_700, 15_000, 2L);
        });

        assertFalse(event.isCrossedThreshold());
        assertTrue(event.isDeltaExceedsThreshold());
        assertEquals(-10_000, event.getSizeDelta());
        assertEquals(OrderWallChangeEvent.Type.REPLACED_SMALLER, event.getType());
    }

    @Test
    void deltaEqualToThresholdDoesNotAlertWithoutCrossing() throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertSeen = new CountDownLatch(1);
        OrderWallChangeTracker tracker = newTracker(TEST_SURVIVAL_MS, event -> {
            events.add(event);
            alertSeen.countDown();
        });

        try {
            tracker.onDepth(true, 12_700, 10_000, 1L);
            tracker.markReady();
            tracker.onDepth(true, 12_700, 15_000, 2L);

            assertFalse(alertSeen.await(300, TimeUnit.MILLISECONDS));
            assertTrue(events.isEmpty());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void tradeDrivenDecreaseIsRecordedInsteadOfDiscarded() throws Exception {
        OrderWallChangeEvent event = captureSingleEvent(tracker -> {
            tracker.onDepth(false, 9_730, 8_000, 1L);
            tracker.markReady();
            tracker.onTrade(9_730, 8_000, true);
            tracker.onDepth(false, 9_730, 0, 2L);
        });

        assertTrue(event.isTradeConsumption());
        assertEquals(8_000, event.getTradedSize());
        assertFalse(event.isActiveLiquidityAlert());
    }

    @Test
    void cancellationDrivenDecreaseIsNotMarkedAsTradeConsumption() throws Exception {
        OrderWallChangeEvent event = captureSingleEvent(tracker -> {
            tracker.onDepth(true, 11_020, 8_000, 1L);
            tracker.markReady();
            tracker.onDepth(true, 11_020, 0, 2L);
        });

        assertFalse(event.isTradeConsumption());
    }

    @Test
    void matchingEnabledWallBreakRetainsDedicatedTypeAndTradeFlag() throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertSeen = new CountDownLatch(1);
        OrderWallChangeTracker tracker = new OrderWallChangeTracker(
                "TEST", 0.01, () -> LARGE_THRESHOLD, 0, TEST_SURVIVAL_MS,
                event -> {
                    events.add(event);
                    alertSeen.countDown();
                }, bidWall -> bidWall, () -> 110.0, () -> 90.0);

        try {
            tracker.onDepth(true, 11_020, 8_000, 1L);
            tracker.markReady();
            tracker.onTrade(11_020, 8_000, false);
            tracker.onDepth(true, 11_020, 0, 2L);

            assertTrue(alertSeen.await(500, TimeUnit.MILLISECONDS));
            assertEquals(OrderWallChangeEvent.Type.BID_BREAKDOWN, events.get(0).getType());
            assertTrue(events.get(0).isTradeConsumption());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void computesAllFourActiveLabelTypesFromLiveDayRange() throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertsSeen = new CountDownLatch(4);
        OrderWallChangeTracker tracker = new OrderWallChangeTracker(
                "TEST", 0.01, () -> LARGE_THRESHOLD, 0, TEST_SURVIVAL_MS,
                event -> {
                    events.add(event);
                    alertsSeen.countDown();
                }, bidWall -> false, () -> 110.0, () -> 90.0);

        try {
            tracker.onDepth(true, 10_200, 8_000, 1L);
            tracker.onDepth(false, 10_300, 8_000, 2L);
            tracker.markReady();

            tracker.onDepth(true, 10_000, 6_000, 3L);
            tracker.onDepth(false, 10_100, 6_000, 4L);
            tracker.onDepth(true, 10_200, 0, 5L);
            tracker.onDepth(false, 10_300, 0, 6L);

            assertTrue(alertsSeen.await(500, TimeUnit.MILLISECONDS));
            assertEquals(4, events.size());
            assertEquals(
                    EnumSet.of(
                            OrderWallChangeEvent.LabelType.BID_UP,
                            OrderWallChangeEvent.LabelType.BID_PULL,
                            OrderWallChangeEvent.LabelType.OFFER_DOWN,
                            OrderWallChangeEvent.LabelType.OFFER_PULL),
                    EnumSet.copyOf(events.stream()
                            .map(OrderWallChangeEvent::getLabelType)
                            .collect(java.util.stream.Collectors.toSet())));
            assertTrue(events.stream().allMatch(OrderWallChangeEvent::isWithinDayRange));
            assertTrue(events.stream().allMatch(OrderWallChangeEvent::isActiveLiquidityAlert));
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void combinesMatchedPullAndAddIntoDirectionalMovedOrderEvents() throws Exception {
        assertMovedOrder(true, 10_000, 10_010,
                OrderWallChangeEvent.Type.BID_MOVED_UP,
                OrderWallChangeEvent.LabelType.BID_UP);
        assertMovedOrder(true, 10_100, 10_090,
                OrderWallChangeEvent.Type.BID_MOVED_DOWN,
                OrderWallChangeEvent.LabelType.BID_DOWN);
        assertMovedOrder(false, 10_200, 10_210,
                OrderWallChangeEvent.Type.OFFER_MOVED_UP,
                OrderWallChangeEvent.LabelType.OFFER_UP);
        assertMovedOrder(false, 10_300, 10_290,
                OrderWallChangeEvent.Type.OFFER_MOVED_DOWN,
                OrderWallChangeEvent.LabelType.OFFER_DOWN);
    }

    @Test
    void doesNotCombineTradeConsumedPullWithNewLiquidity() throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertsSeen = new CountDownLatch(2);
        OrderWallChangeTracker tracker = new OrderWallChangeTracker(
                "TEST", 0.01, () -> LARGE_THRESHOLD, 0, TEST_SURVIVAL_MS,
                event -> {
                    events.add(event);
                    alertsSeen.countDown();
                }, bidWall -> false, () -> 110.0, () -> 90.0);
        try {
            tracker.onDepth(false, 10_200, 8_000, 1L);
            tracker.markReady();
            tracker.onTrade(10_200, 8_000, true);
            tracker.onDepth(false, 10_200, 0, 2L);
            tracker.onDepth(false, 10_210, 8_000, 3L);

            assertTrue(alertsSeen.await(500, TimeUnit.MILLISECONDS));
            assertEquals(2, events.size());
            assertTrue(events.stream().noneMatch(OrderWallChangeEvent::isMovedOrder));
            assertTrue(events.stream().anyMatch(OrderWallChangeEvent::isTradeConsumption));
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void bidAtOrBelowLowAndOfferAtOrAboveHighAreOutsideActiveRange() throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertsSeen = new CountDownLatch(2);
        OrderWallChangeTracker tracker = new OrderWallChangeTracker(
                "TEST", 0.01, () -> LARGE_THRESHOLD, 0, TEST_SURVIVAL_MS,
                event -> {
                    events.add(event);
                    alertsSeen.countDown();
                }, bidWall -> false, () -> 110.0, () -> 90.0);

        try {
            tracker.markReady();
            tracker.onDepth(true, 9_000, 6_000, 1L);
            tracker.onDepth(false, 11_000, 6_000, 2L);

            assertTrue(alertsSeen.await(500, TimeUnit.MILLISECONDS));
            assertTrue(events.stream().noneMatch(OrderWallChangeEvent::isWithinDayRange));
            assertTrue(events.stream().noneMatch(OrderWallChangeEvent::isActiveLiquidityAlert));
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void fifthLargestOrderSetsDynamicThresholdCappedAtTenThousand() throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertSeen = new CountDownLatch(1);
        OrderWallChangeTracker tracker = newRankedTracker(TEST_SURVIVAL_MS, event -> {
            events.add(event);
            alertSeen.countDown();
        });

        try {
            tracker.onDepth(false, 19_400, 50_000, 1L);
            tracker.onDepth(false, 19_410, 40_000, 2L);
            tracker.onDepth(false, 19_420, 30_000, 3L);
            tracker.onDepth(false, 19_430, 20_000, 4L);
            tracker.onDepth(false, 19_440, 10_000, 5L);
            tracker.onDepth(false, 19_450, 1_000, 6L);
            tracker.markReady();
            tracker.onDepth(false, 19_460, 6_000, 7L);

            assertFalse(alertSeen.await(300, TimeUnit.MILLISECONDS));
            assertTrue(events.isEmpty());

            tracker.onDepth(false, 19_470, 12_000, 8L);
            assertTrue(alertSeen.await(500, TimeUnit.MILLISECONDS));
            assertEquals(10_000, events.get(0).getEffectiveThreshold());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void rankedThresholdFallsBackToFiveThousandWithFewerThanFiveOrders() throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertSeen = new CountDownLatch(1);
        OrderWallChangeTracker tracker = newRankedTracker(TEST_SURVIVAL_MS, event -> {
            events.add(event);
            alertSeen.countDown();
        });

        try {
            tracker.markReady();
            tracker.onDepth(true, 10_000, 6_000, 1L);

            assertTrue(alertSeen.await(500, TimeUnit.MILLISECONDS));
            assertEquals(LARGE_THRESHOLD, events.get(0).getEffectiveThreshold());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void thresholdSupplierUpdatesFutureDecisions() throws Exception {
        AtomicInteger thresholdFloor = new AtomicInteger(LARGE_THRESHOLD);
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertSeen = new CountDownLatch(1);
        OrderWallChangeTracker tracker = new OrderWallChangeTracker(
                "TEST", 0.01, thresholdFloor::get, 0, TEST_SURVIVAL_MS,
                event -> {
                    events.add(event);
                    alertSeen.countDown();
                }, bidWall -> false, () -> 110.0, () -> 90.0);

        try {
            tracker.markReady();
            thresholdFloor.set(10_000);
            tracker.onDepth(true, 12_700, 7_000, 1L);
            assertFalse(alertSeen.await(200, TimeUnit.MILLISECONDS));

            tracker.onDepth(true, 12_710, 11_000, 2L);
            assertTrue(alertSeen.await(500, TimeUnit.MILLISECONDS));
            assertEquals(10_000, events.get(0).getEffectiveThreshold());
        } finally {
            tracker.shutdown();
        }
    }

    private static OrderWallChangeEvent captureSingleEvent(TrackerAction action) throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertSeen = new CountDownLatch(1);
        OrderWallChangeTracker tracker = newTracker(TEST_SURVIVAL_MS, event -> {
            events.add(event);
            alertSeen.countDown();
        });
        try {
            action.run(tracker);
            assertTrue(alertSeen.await(500, TimeUnit.MILLISECONDS));
            assertEquals(1, events.size());
            return events.get(0);
        } finally {
            tracker.shutdown();
        }
    }

    private static void assertMovedOrder(boolean bid, int sourcePriceTick, int destinationPriceTick,
                                         OrderWallChangeEvent.Type expectedType,
                                         OrderWallChangeEvent.LabelType expectedLabelType)
            throws Exception {
        List<OrderWallChangeEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch alertSeen = new CountDownLatch(1);
        OrderWallChangeTracker tracker = new OrderWallChangeTracker(
                "TEST", 0.01, () -> LARGE_THRESHOLD, 0, TEST_SURVIVAL_MS,
                event -> {
                    events.add(event);
                    alertSeen.countDown();
                }, bidWall -> false, () -> 110.0, () -> 90.0);
        try {
            tracker.onDepth(bid, sourcePriceTick, 6_000, 1L);
            tracker.markReady();
            tracker.onDepth(bid, sourcePriceTick, 0, 2L);
            tracker.onDepth(bid, destinationPriceTick, 6_000, 3L);

            assertTrue(alertSeen.await(500, TimeUnit.MILLISECONDS));
            Thread.sleep(100);
            assertEquals(1, events.size());
            OrderWallChangeEvent event = events.get(0);
            assertTrue(event.isMovedOrder());
            assertEquals(expectedType, event.getType());
            assertEquals(expectedLabelType, event.getLabelType());
            assertEquals(sourcePriceTick, event.getPreviousPriceTick());
            assertEquals(destinationPriceTick, event.getPriceTick());
            assertEquals(6_000, event.getMovedSize());
            assertFalse(event.isTradeConsumption());
            assertTrue(event.isActiveLiquidityAlert());
        } finally {
            tracker.shutdown();
        }
    }

    private static OrderWallChangeTracker newTracker(
            long survivalMs,
            java.util.function.Consumer<OrderWallChangeEvent> consumer) {
        return new OrderWallChangeTracker(
                "TEST", 0.01, LARGE_THRESHOLD, 0.50,
                survivalMs, survivalMs, consumer);
    }

    private static OrderWallChangeTracker newRankedTracker(
            long survivalMs,
            java.util.function.Consumer<OrderWallChangeEvent> consumer) {
        return new OrderWallChangeTracker(
                "TEST", 0.01, () -> LARGE_THRESHOLD, 5, survivalMs,
                consumer, bidWall -> false, () -> 110.0, () -> 90.0);
    }

    @FunctionalInterface
    private interface TrackerAction {
        void run(OrderWallChangeTracker tracker);
    }
}
