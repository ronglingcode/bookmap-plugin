package com.bookmap.plugin.rong.orderwall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
}
