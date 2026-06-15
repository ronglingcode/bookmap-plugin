package com.bookmap.plugin.rong.orderwall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class OrderWallLabelTrackerTest {

    @Test
    void tracksLevelsOnlyWhenTheyExceedConfiguredMinimumSize() {
        OrderWallLabelStore store = new OrderWallLabelStore();
        OrderWallLabelTracker tracker = new OrderWallLabelTracker(
                "TEST", 0.01, store, 5_000, 2_000);

        try {
            onDepth(tracker, true, 12_700, 5_000, 1L);
            assertNull(store.getActiveLabel("TEST", true, 12_700));

            onDepth(tracker, true, 12_700, 5_001, 2L);
            OrderWallLabel label = store.getActiveLabel("TEST", true, 12_700);

            assertNotNull(label);
            assertEquals(5_001, label.getCurrentSize());
            assertEquals(5_001, label.getPeakSize());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void sizePathKeepsPeakWhenWallIsConsumedAfterBreakout() {
        OrderWallLabelStore store = new OrderWallLabelStore();
        OrderWallLabelTracker tracker = new OrderWallLabelTracker(
                "TEST", 0.01, store, 0, 2_000);

        try {
            onDepth(tracker, false, 47_000, 79_000, 1L);
            onDepth(tracker, false, 47_000, 106_000, 2L);
            onDepth(tracker, false, 47_000, 107_000, 3L);
            onDepth(tracker, false, 47_000, 108_000, 4L);

            onDepth(tracker, false, 47_000, 79_000, 5L);
            onDepth(tracker, false, 47_000, 11_000, 6L);
            onDepth(tracker, false, 47_000, 5_000, 7L);
            onDepth(tracker, false, 47_000, 2_000, 8L);

            OrderWallLabel label = store.getActiveLabel("TEST", false, 47_000);

            assertEquals(108_000, label.getPeakSize());
            assertEquals(Arrays.asList(79, 106, 107, 108), label.getSizePath());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void stableDecreaseIsRecordedAfterDebounce() throws Exception {
        OrderWallLabelStore store = new OrderWallLabelStore();
        CountDownLatch changeSeen = new CountDownLatch(1);
        OrderWallLabelTracker tracker = new OrderWallLabelTracker(
                "TEST", 0.01, store, 0, 2_000, 30, changeSeen::countDown);

        try {
            onDepth(tracker, false, 47_000, 79_000, 1L);
            onDepth(tracker, false, 47_000, 108_000, 2L);
            onDepth(tracker, false, 47_000, 11_000, 3L);

            assertEquals(Arrays.asList(79, 108),
                    store.getActiveLabel("TEST", false, 47_000).getSizePath());

            assertTrue(changeSeen.await(500, TimeUnit.MILLISECONDS));
            assertEquals(Arrays.asList(79, 108, 11),
                    store.getActiveLabel("TEST", false, 47_000).getSizePath());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void transientDecreaseIsIgnoredWhenSizeChangesBeforeDebounce() throws Exception {
        OrderWallLabelStore store = new OrderWallLabelStore();
        CountDownLatch changeSeen = new CountDownLatch(1);
        OrderWallLabelTracker tracker = new OrderWallLabelTracker(
                "TEST", 0.01, store, 0, 2_000, 90, changeSeen::countDown);

        try {
            onDepth(tracker, false, 47_000, 79_000, 1L);
            onDepth(tracker, false, 47_000, 108_000, 2L);
            onDepth(tracker, false, 47_000, 11_000, 3L);
            Thread.sleep(20);
            onDepth(tracker, false, 47_000, 108_000, 4L);

            assertFalse(changeSeen.await(200, TimeUnit.MILLISECONDS));
            assertEquals(Arrays.asList(79, 108),
                    store.getActiveLabel("TEST", false, 47_000).getSizePath());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void zeroDecreaseIsRecordedImmediately() {
        OrderWallLabelStore store = new OrderWallLabelStore();
        OrderWallLabelTracker tracker = new OrderWallLabelTracker(
                "TEST", 0.01, store, 0, 2_000, 1_000, null);

        try {
            onDepth(tracker, false, 47_000, 79_000, 1L);
            onDepth(tracker, false, 47_000, 108_000, 2L);
            onDepth(tracker, false, 47_000, 0, 3L);

            OrderWallLabel label = store.getLabels("TEST").get(0);

            assertEquals(Arrays.asList(79, 108, 0), label.getSizePath());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void laterHigherReloadStillExtendsGrowthPath() {
        OrderWallLabelStore store = new OrderWallLabelStore();
        OrderWallLabelTracker tracker = new OrderWallLabelTracker(
                "TEST", 0.01, store, 0, 2_000);

        try {
            onDepth(tracker, false, 47_000, 79_000, 1L);
            onDepth(tracker, false, 47_000, 108_000, 2L);
            onDepth(tracker, false, 47_000, 11_000, 3L);
            onDepth(tracker, false, 47_000, 120_000, 4L);

            OrderWallLabel label = store.getActiveLabel("TEST", false, 47_000);

            assertEquals(Arrays.asList(79, 108, 120), label.getSizePath());
        } finally {
            tracker.shutdown();
        }
    }

    private static void onDepth(OrderWallLabelTracker tracker,
                                boolean isBid, int priceTick, int size, long eventTimeNs) {
        tracker.onDepth(isBid, priceTick, size, eventTimeNs);
    }
}
