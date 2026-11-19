package com.bookmap.plugin.rong;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BackgroundChartRendererTest {
    @Test void redrawRequestsReturnWhileRenderingAndCoalesceWithoutLosingTheNextUpdate() throws Exception {
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1), second = new CountDownLatch(1);
        AtomicInteger renders = new AtomicInteger();
        try (BackgroundChartRenderer renderer = new BackgroundChartRenderer("render-test")) {
            Runnable redraw = () -> {
                int count = renders.incrementAndGet();
                if (count == 1) {
                    running.countDown();
                    try { release.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                } else second.countDown();
            };
            renderer.request(redraw);
            assertTrue(running.await(3, TimeUnit.SECONDS));
            for (int i = 0; i < 1000; i++) renderer.request(redraw);
            assertEquals(1, renders.get());
            release.countDown();
            assertTrue(second.await(3, TimeUnit.SECONDS));
            assertEquals(2, renders.get());
        } finally { release.countDown(); }
    }
}
