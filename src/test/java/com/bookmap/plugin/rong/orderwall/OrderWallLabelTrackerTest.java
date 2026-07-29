package com.bookmap.plugin.rong.orderwall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class OrderWallLabelTrackerTest {

    @Test
    void tracksLevelsAtOrAboveConfiguredMinimumSize() {
        OrderWallLabelStore store = new OrderWallLabelStore();
        OrderWallLabelTracker tracker = newImmediateTracker(store, 5_000);

        try {
            onDepth(tracker, true, 12_700, 4_999, 1L);
            assertNull(store.getActiveLabel("TEST", true, 12_700));

            onDepth(tracker, true, 12_700, 5_000, 2L);
            OrderWallLabel label = store.getActiveLabel("TEST", true, 12_700);

            assertNotNull(label);
            assertEquals(5_000, label.getCurrentSize());
            assertEquals(5_000, label.getPeakSize());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void usesCurrentThresholdForFutureDepthDecisions() {
        OrderWallLabelStore store = new OrderWallLabelStore();
        AtomicInteger minimumSize = new AtomicInteger(5_000);
        OrderWallLabelTracker tracker = newImmediateTracker(store, minimumSize);

        try {
            onDepth(tracker, true, 12_700, 6_000, 1L);
            assertNotNull(store.getActiveLabel("TEST", true, 12_700));

            minimumSize.set(14_600);
            onDepth(tracker, true, 12_700, 6_000, 2L);
            assertNull(store.getActiveLabel("TEST", true, 12_700));

            onDepth(tracker, true, 12_701, 14_599, 3L);
            assertNull(store.getActiveLabel("TEST", true, 12_701));

            onDepth(tracker, true, 12_701, 14_600, 4L);
            assertNotNull(store.getActiveLabel("TEST", true, 12_701));
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void pendingLabelMustStillPassCurrentThresholdWhenItMatures() {
        OrderWallLabelStore store = new OrderWallLabelStore();
        AtomicInteger minimumSize = new AtomicInteger(5_000);
        OrderWallLabelTracker tracker = newTracker(
                store, minimumSize, 1_000, 1_000, null);

        try {
            onDepth(tracker, false, 11_730, 6_000, ns(10_000));
            minimumSize.set(14_600);

            assertFalse(tracker.onTimestamp(ns(11_000)));
            assertNull(store.getActiveLabel("TEST", false, 11_730));
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void skipsLargeOrderThatDisappearsBeforeLabelLifetime() {
        OrderWallLabelStore store = new OrderWallLabelStore();
        OrderWallLabelTracker tracker = newTracker(store, 5_000, 1_000, 1_000, null);

        try {
            onDepth(tracker, false, 11_730, 5_500, ns(10_000));
            assertNull(store.getActiveLabel("TEST", false, 11_730));

            onDepth(tracker, false, 11_730, 0, ns(10_800));

            assertFalse(tracker.onTimestamp(ns(11_500)));
            assertTrue(store.getLabels("TEST").isEmpty());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void labelsOnlyAfterLevelSurvivesOneSecond() {
        OrderWallLabelStore store = new OrderWallLabelStore();
        OrderWallLabelTracker tracker = newTracker(store, 5_000, 1_000, 1_000, null);

        try {
            onDepth(tracker, false, 11_730, 5_500, ns(10_000));

            assertFalse(tracker.onTimestamp(ns(10_999)));
            assertNull(store.getActiveLabel("TEST", false, 11_730));

            assertTrue(tracker.onTimestamp(ns(11_000)));
            OrderWallLabel label = store.getActiveLabel("TEST", false, 11_730);

            assertNotNull(label);
            assertEquals(5_500, label.getCurrentSize());
            assertEquals(5_500, label.getPeakSize());
            assertEquals(ns(10_000), label.getStartTimeNs());
            assertEquals(ns(11_000), label.getEndTimeNs());
        } finally {
            tracker.shutdown();
        }
    }

    @Test
    void sizePathKeepsPeakWhenWallIsConsumedAfterBreakout() {
        OrderWallLabelStore store = new OrderWallLabelStore();
        OrderWallLabelTracker tracker = newImmediateTracker(store, 0);

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
        OrderWallLabelTracker tracker = newImmediateTracker(store, 0, 30, changeSeen::countDown);

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
        OrderWallLabelTracker tracker = newImmediateTracker(store, 0, 90, changeSeen::countDown);

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
        OrderWallLabelTracker tracker = newImmediateTracker(store, 0, 1_000, null);

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
        OrderWallLabelTracker tracker = newImmediateTracker(store, 0);

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

    private static OrderWallLabelTracker newImmediateTracker(OrderWallLabelStore store, int minimumSize) {
        return newImmediateTracker(store, minimumSize, 1_000, null);
    }

    private static OrderWallLabelTracker newImmediateTracker(OrderWallLabelStore store, int minimumSize,
                                                            long decreaseStabilityMs,
                                                            Runnable labelChangeListener) {
        return newTracker(store, minimumSize, decreaseStabilityMs, 0, labelChangeListener);
    }

    private static OrderWallLabelTracker newImmediateTracker(
            OrderWallLabelStore store, AtomicInteger minimumSize) {
        return newTracker(store, minimumSize, 1_000, 0, null);
    }

    private static OrderWallLabelTracker newTracker(OrderWallLabelStore store, int minimumSize,
                                                    long decreaseStabilityMs, long labelMinLifetimeMs,
                                                    Runnable labelChangeListener) {
        return new OrderWallLabelTracker(
                "TEST",
                0.01,
                store,
                minimumSize,
                2_000,
                decreaseStabilityMs,
                labelMinLifetimeMs,
                labelChangeListener);
    }

    private static OrderWallLabelTracker newTracker(
            OrderWallLabelStore store, AtomicInteger minimumSize,
            long decreaseStabilityMs, long labelMinLifetimeMs,
            Runnable labelChangeListener) {
        return new OrderWallLabelTracker(
                "TEST",
                0.01,
                store,
                minimumSize::get,
                2_000,
                decreaseStabilityMs,
                labelMinLifetimeMs,
                labelChangeListener);
    }

    private static long ns(long millis) {
        return millis * 1_000_000L;
    }
}
