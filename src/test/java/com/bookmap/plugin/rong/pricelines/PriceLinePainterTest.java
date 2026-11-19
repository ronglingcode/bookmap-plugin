package com.bookmap.plugin.rong.pricelines;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainter;

class PriceLinePainterTest {
    @Test
    void filledOrdersDisappearWhenAccountUpdateOverlapsChartRedraw() throws Exception {
        for (PriceLine.LineType type : new PriceLine.LineType[] {
                PriceLine.LineType.ENTRY_ORDER, PriceLine.LineType.EXIT_ORDER }) {
            CanvasFixture canvas = new CanvasFixture();
            PriceLineStore store = new PriceLineStore();
            ScreenSpacePainter painter = painter(store, canvas);
            store.addLine(new PriceLine("PLTR", type, 19937, 199.37));
            waitFor(() -> canvas.visible.size() == 1);

            ExecutorService threads = Executors.newFixedThreadPool(2);
            canvas.pauseRemoval.set(true);
            try {
                Future<?> redraw = threads.submit(() -> painter.onHeatmapFullPixelsWidth(1000));
                redraw.get(1, TimeUnit.SECONDS); // UI callback returns while background rendering is paused.
                assertTrue(canvas.removalStarted.await(5, TimeUnit.SECONDS));
                CountDownLatch fillStarted = new CountDownLatch(1);
                Future<?> fill = threads.submit(() -> {
                    fillStarted.countDown();
                    store.removeByType("PLTR", type);
                });
                assertTrue(fillStarted.await(5, TimeUnit.SECONDS));
                fill.get(1, TimeUnit.SECONDS); // Account updates must not wait for a canvas rebuild.
                canvas.resumeRemoval.countDown();
                redraw.get(5, TimeUnit.SECONDS);
                fill.get(5, TimeUnit.SECONDS);
                assertTrue(store.getLines("PLTR").isEmpty());
                waitFor(canvas.visible::isEmpty);
                painter.onHeatmapFullPixelsWidth(1200);
                assertTrue(canvas.visible.isEmpty());
            } finally {
                canvas.resumeRemoval.countDown();
                threads.shutdownNow();
                assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS));
                painter.dispose(); canvas.factory.shutdown();
            }
        }
    }

    @Test
    void disposedPainterIgnoresLateCallbacksAndRepeatedDisposal() {
        CanvasFixture canvas = new CanvasFixture();
        PriceLineStore store = new PriceLineStore();
        ScreenSpacePainter painter = painter(store, canvas);
        store.addLine(new PriceLine("PLTR", PriceLine.LineType.ENTRY_ORDER, 19937, 199.37));
        painter.dispose();
        painter.onHeatmapFullPixelsWidth(1000);
        ((PriceLineStore.ChangeListener) painter).onLinesChanged("PLTR");
        painter.dispose();
        assertTrue(canvas.visible.isEmpty());
        assertEquals(1, canvas.disposals.get()); canvas.factory.shutdown();
    }

    @Test void addingOneLineReusesExistingShapesAndRenderingRunsInBackground() throws Exception {
        CanvasFixture canvas = new CanvasFixture();
        PriceLineStore store = new PriceLineStore();
        ScreenSpacePainter painter = painter(store, canvas);
        try {
            painter.onHeatmapFullPixelsWidth(1000);
            store.addLine(new PriceLine("PLTR", PriceLine.LineType.KEY_LEVEL, 10000, 100));
            waitFor(() -> canvas.visible.size() == 1);
            Object original = canvas.visible.iterator().next();
            for (int i = 0; i < 100; i++) painter.onHeatmapFullPixelsWidth(1000);
            store.addLine(new PriceLine("PLTR", PriceLine.LineType.KEY_LEVEL, 10000, 100));
            store.addLine(new PriceLine("PLTR", PriceLine.LineType.KEY_LEVEL, 10100, 101));
            waitFor(() -> canvas.visible.size() == 2);
            assertTrue(canvas.visible.contains(original));
            assertEquals(2, canvas.additions.get());
            assertEquals("price-line-renderer", canvas.renderingThread.get());
        } finally { painter.dispose(); canvas.factory.shutdown(); }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }

    private static ScreenSpacePainter painter(PriceLineStore store, CanvasFixture canvas) {
        PriceLinePainter factory = new PriceLinePainter(store);
        canvas.factory = factory;
        factory.registerInstrument("PLTR");
        return factory.createScreenSpacePainter("priceLines_PLTR", "test", type -> canvas.proxy);
    }

    private static class CanvasFixture {
        final Set<Object> visible = ConcurrentHashMap.newKeySet();
        final AtomicBoolean pauseRemoval = new AtomicBoolean();
        final AtomicInteger disposals = new AtomicInteger();
        final AtomicInteger additions = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<String> renderingThread = new java.util.concurrent.atomic.AtomicReference<>();
        PriceLinePainter factory;
        final CountDownLatch removalStarted = new CountDownLatch(1);
        final CountDownLatch resumeRemoval = new CountDownLatch(1);
        final ScreenSpaceCanvas proxy = (ScreenSpaceCanvas) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { ScreenSpaceCanvas.class }, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "addShape": renderingThread.set(Thread.currentThread().getName()); additions.incrementAndGet(); visible.add(args[0]); break;
                        case "removeShape":
                            visible.remove(args[0]);
                            if (pauseRemoval.compareAndSet(true, false)) {
                                removalStarted.countDown();
                                if (!resumeRemoval.await(5, TimeUnit.SECONDS)) {
                                    throw new AssertionError("Timed out waiting to resume redraw");
                                }
                            }
                            break;
                        case "dispose": disposals.incrementAndGet(); break;
                        case "getUniqueId": return 1L;
                        default: break;
                    }
                    return null;
                });
    }
}
