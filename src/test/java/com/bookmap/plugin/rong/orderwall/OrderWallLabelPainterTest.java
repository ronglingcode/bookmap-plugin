package com.bookmap.plugin.rong.orderwall;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import com.bookmap.plugin.rong.IndicatorConfig;
import velox.api.layer1.layers.strategies.interfaces.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OrderWallLabelPainterTest {

    @Test void uiRequestsCoalesceAndImagesAndShapesAreBuiltOnRenderWorker() throws Exception {
        OrderWallLabelStore store = new OrderWallLabelStore();
        OrderWallChangeStore changes = new OrderWallChangeStore();
        IndicatorConfig config = new IndicatorConfig();
        CountDownLatch drawn = new CountDownLatch(1);
        AtomicReference<Thread> rendering = new AtomicReference<>();
        AtomicInteger added = new AtomicInteger();
        ScreenSpaceCanvas canvas = (ScreenSpaceCanvas) Proxy.newProxyInstance(
                ScreenSpaceCanvas.class.getClassLoader(), new Class<?>[]{ScreenSpaceCanvas.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addShape")) {
                        rendering.set(Thread.currentThread()); added.incrementAndGet(); drawn.countDown();
                    }
                    if (method.getName().equals("getUniqueId")) return 1L;
                    return null;
                });
        OrderWallLabelPainter painter = new OrderWallLabelPainter(store, config, changes);
        ScreenSpacePainter[] instance = new ScreenSpacePainter[1];
        try {
            painter.registerInstrument("TEST");
            store.putLabel(new OrderWallLabel("TEST", true, 10000, 100, 5000, 5000, 1, 2, 3));
            SwingUtilities.invokeAndWait(() -> {
                instance[0] = painter.createScreenSpacePainter("TEST", "wallLabels_TEST", type -> canvas);
                for (int i = 0; i < 100; i++) {
                    instance[0].onHeatmapPriceBottom(9900);
                    instance[0].onHeatmapPriceHeight(200);
                    painter.refreshInstrument("TEST");
                }
            });
            assertTrue(drawn.await(3, TimeUnit.SECONDS));
            assertEquals("wall-label-painter", rendering.get().getName());
            assertTrue(added.get() < 100, "UI requests must be coalesced instead of rendering each call");
        } finally {
            painter.shutdown();
            if (instance[0] != null) instance[0].dispose();
        }
    }

    @Test void pruningKeepsActiveLevelsAndTheNewestHistoricalSegments() {
        OrderWallLabelStore store = new OrderWallLabelStore();
        store.putLabel(new OrderWallLabel("active", "TEST", true, 10000, 100, 5000, 5000, 1, 1, 1, true));
        for (int i = 0; i < 10; i++) {
            store.putLabel(new OrderWallLabel("old-" + i, "TEST", true, 10000, 100, 0, 5000, i, i + 2, 1, true));
        }
        store.pruneHistorical("TEST", 2);
        assertEquals(3, store.getLabels("TEST").size());
        assertNotNull(store.getActiveLabel("TEST", true, 10000));
        assertNotNull(store.getLabel("TEST", "old-9"));
        assertNotNull(store.getLabel("TEST", "old-8"));
    }

    @Test void addingCurrentWallRetainsHistoricalLabelShape() throws Exception {
        OrderWallLabelStore store = new OrderWallLabelStore();
        OrderWallLabel historical = new OrderWallLabel("history", "TEST", true, 10000, 100,
                0, 6000, 1, 2, 3, true);
        store.putLabel(historical); store.markDisplayed("TEST", historical.getId());
        java.util.Set<Object> shapes = java.util.concurrent.ConcurrentHashMap.newKeySet();
        AtomicInteger added = new AtomicInteger();
        ScreenSpaceCanvas canvas = (ScreenSpaceCanvas) Proxy.newProxyInstance(ScreenSpaceCanvas.class.getClassLoader(),
                new Class<?>[]{ScreenSpaceCanvas.class}, (proxy, method, args) -> {
                    if (method.getName().equals("addShape")) { added.incrementAndGet(); shapes.add(args[0]); }
                    else if (method.getName().equals("removeShape")) shapes.remove(args[0]);
                    else if (method.getName().equals("getUniqueId")) return 1L;
                    return null;
                });
        OrderWallLabelPainter painter = new OrderWallLabelPainter(store, new IndicatorConfig(), new OrderWallChangeStore());
        painter.registerInstrument("TEST");
        ScreenSpacePainter instance = painter.createScreenSpacePainter("TEST", "wallLabels_TEST", type -> canvas);
        try {
            waitFor(() -> shapes.size() == 1);
            Object original = shapes.iterator().next();
            store.putLabel(new OrderWallLabel("TEST", false, 10100, 101, 7000, 7000, 1, 2, 3));
            painter.refreshInstrument("TEST");
            waitFor(() -> shapes.size() == 2);
            assertTrue(shapes.contains(original)); assertEquals(2, added.get());
            assertNotNull(store.getLabel("TEST", "history"));
        } finally { painter.shutdown(); instance.dispose(); }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }

    @Test
    void compactSizePathKeepsPeakVisibleAfterStableDecreases() {
        OrderWallLabel label = new OrderWallLabel(
                "id",
                "TEST",
                false,
                47_000,
                470.00,
                2_000,
                108_000,
                1L,
                2L,
                3L,
                true,
                Arrays.asList(79, 106, 107, 108, 11, 5, 2));

        assertEquals("79 -> 108 -> 5 -> 2", OrderWallLabelPainter.formatSizePath(label));
    }
}
