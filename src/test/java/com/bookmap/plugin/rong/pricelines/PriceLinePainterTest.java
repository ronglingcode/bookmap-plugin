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
            assertEquals(1, canvas.visible.size());

            ExecutorService threads = Executors.newFixedThreadPool(2);
            canvas.pauseRemoval.set(true);
            try {
                Future<?> redraw = threads.submit(() -> painter.onHeatmapFullPixelsWidth(1000));
                assertTrue(canvas.removalStarted.await(5, TimeUnit.SECONDS));
                CountDownLatch fillStarted = new CountDownLatch(1);
                Future<?> fill = threads.submit(() -> {
                    fillStarted.countDown();
                    store.removeByType("PLTR", type);
                });
                assertTrue(fillStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> fill.get(200, TimeUnit.MILLISECONDS),
                        "Account redraw must wait for the current canvas rebuild");
                canvas.resumeRemoval.countDown();
                redraw.get(5, TimeUnit.SECONDS);
                fill.get(5, TimeUnit.SECONDS);
                assertTrue(store.getLines("PLTR").isEmpty());
                assertTrue(canvas.visible.isEmpty(), "Filled order left a canvas shape: " + type);
                painter.onHeatmapFullPixelsWidth(1200);
                assertTrue(canvas.visible.isEmpty());
            } finally {
                canvas.resumeRemoval.countDown();
                threads.shutdownNow();
                assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS));
                painter.dispose();
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
        assertEquals(1, canvas.disposals.get());
    }

    private static ScreenSpacePainter painter(PriceLineStore store, CanvasFixture canvas) {
        PriceLinePainter factory = new PriceLinePainter(store);
        factory.registerInstrument("PLTR");
        return factory.createScreenSpacePainter("priceLines_PLTR", "test", type -> canvas.proxy);
    }

    private static class CanvasFixture {
        final Set<Object> visible = ConcurrentHashMap.newKeySet();
        final AtomicBoolean pauseRemoval = new AtomicBoolean();
        final AtomicInteger disposals = new AtomicInteger();
        final CountDownLatch removalStarted = new CountDownLatch(1);
        final CountDownLatch resumeRemoval = new CountDownLatch(1);
        final ScreenSpaceCanvas proxy = (ScreenSpaceCanvas) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { ScreenSpaceCanvas.class }, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "addShape": visible.add(args[0]); break;
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
