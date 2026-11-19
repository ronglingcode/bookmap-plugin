package com.bookmap.plugin.rong;

import com.bookmap.plugin.rong.executions.*;
import com.bookmap.plugin.rong.pricelines.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import velox.api.layer1.layers.strategies.interfaces.*;
import static org.junit.jupiter.api.Assertions.*;

class BackgroundPainterReuseTest {
    static void waitFor(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
    static final class Canvas {
        final Set<Object> visible = ConcurrentHashMap.newKeySet();
        final AtomicInteger added = new AtomicInteger();
        final AtomicReference<String> thread = new AtomicReference<>();
        final ScreenSpaceCanvas proxy = (ScreenSpaceCanvas) Proxy.newProxyInstance(
                ScreenSpaceCanvas.class.getClassLoader(), new Class<?>[]{ScreenSpaceCanvas.class}, (p, method, args) -> {
                    if (method.getName().equals("addShape")) {
                        thread.set(Thread.currentThread().getName()); added.incrementAndGet(); visible.add(args[0]);
                    } else if (method.getName().equals("removeShape")) visible.remove(args[0]);
                    else if (method.getName().equals("getUniqueId")) return 1L;
                    return null;
                });
    }
    @Test void zoneUpdatesRetainUnchangedShapesAndNeverDrawOnTheCallerThread() throws Exception {
        Canvas canvas = new Canvas();
        PriceZoneStore store = new PriceZoneStore();
        PriceZonePainter factory = new PriceZonePainter(store);
        factory.registerInstrument("TEST");
        ScreenSpacePainter painter = factory.createScreenSpacePainter("priceZones_TEST", "test", type -> canvas.proxy);
        PriceZone first = new PriceZone("TEST", 10000, 10100, 100, 101, "zone", null);
        try {
            painter.onHeatmapFullPixelsWidth(1000);
            store.replaceAll("TEST", List.of(first));
            waitFor(() -> canvas.visible.size() == 5);
            Set<Object> original = Set.copyOf(canvas.visible);
            for (int i = 0; i < 100; i++) painter.onHeatmapFullPixelsWidth(1000);
            PriceZone next = new PriceZone("TEST", 10200, 10300, 102, 103, "next", null);
            store.replaceAll("TEST", List.of(new PriceZone("TEST", 10000, 10100, 100, 101, "zone", null), next));
            waitFor(() -> canvas.visible.size() == 10);
            assertTrue(canvas.visible.containsAll(original));
            assertEquals(10, canvas.added.get());
            assertEquals("price-zone-renderer", canvas.thread.get());
            store.clearAll("TEST"); waitFor(canvas.visible::isEmpty);
        } finally { painter.dispose(); factory.shutdown(); }
    }
    @Test void fillUpdatesRetainUnchangedMarkersAndHideRemovedExecutions() throws Exception {
        Canvas canvas = new Canvas();
        FilledExecutionStore store = new FilledExecutionStore();
        FilledExecutionPainter factory = new FilledExecutionPainter(store, new IndicatorConfig());
        factory.registerInstrument("TEST");
        ScreenSpacePainter painter = factory.createScreenSpacePainter("TEST", "filledExecutions_TEST", type -> canvas.proxy);
        long now = System.currentTimeMillis() * 1_000_000L;
        FilledExecutionMarker first = new FilledExecutionMarker("TEST", 10000, 100, 10, true, true, now);
        try {
            store.replaceAll("TEST", List.of(first)); waitFor(() -> canvas.visible.size() == 1);
            Object original = canvas.visible.iterator().next();
            for (int i = 0; i < 100; i++) painter.onHeatmapPriceBottom(9900);
            FilledExecutionMarker next = new FilledExecutionMarker("TEST", 10200, 102, 10, false, false, now + 5_000_000_000L);
            store.replaceAll("TEST", List.of(first, next)); waitFor(() -> canvas.visible.size() == 2);
            assertTrue(canvas.visible.contains(original)); assertEquals(2, canvas.added.get());
            assertEquals("filled-execution-painter", canvas.thread.get());
            store.clearAll("TEST"); waitFor(canvas.visible::isEmpty);
        } finally { painter.dispose(); factory.shutdown(); }
    }
}
