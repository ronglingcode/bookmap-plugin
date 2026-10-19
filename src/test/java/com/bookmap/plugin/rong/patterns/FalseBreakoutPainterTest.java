package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import velox.api.layer1.layers.strategies.interfaces.*;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.*;

class FalseBreakoutPainterTest {
    @Test void offerHighlightHandsOffToSharedNextBidAndDisposeIsIdempotent() {
        FalseBreakoutMonitor monitor = FalseBreakoutMonitorTest.started();
        List<CanvasIcon> shapes = new CopyOnWriteArrayList<>(); AtomicInteger disposals = new AtomicInteger();
        ScreenSpaceCanvas canvas = (ScreenSpaceCanvas) Proxy.newProxyInstance(ScreenSpaceCanvas.class.getClassLoader(), new Class<?>[]{ScreenSpaceCanvas.class}, (object, method, args) -> {
            if (method.getName().equals("addShape")) shapes.add((CanvasIcon) args[0]);
            if (method.getName().equals("removeShape")) shapes.remove(args[0]);
            if (method.getName().equals("dispose")) disposals.incrementAndGet(); return null;
        });
        ScreenSpaceCanvasFactory factory = (ScreenSpaceCanvasFactory) Proxy.newProxyInstance(ScreenSpaceCanvasFactory.class.getClassLoader(), new Class<?>[]{ScreenSpaceCanvasFactory.class}, (object, method, args) -> canvas);
        FalseBreakoutPainter painter = new FalseBreakoutPainter(monitor);
        try {
            ScreenSpacePainter screen = painter.createScreenSpacePainter("TEST", "falseBreakout_TEST", factory);
            monitor.onDepth(false, 110, 6000, FalseBreakoutMonitorTest.OPEN, true); painter.refreshNow();
            assertEquals(2, shapes.size());
            assertTrue(shapes.stream().anyMatch(shape -> shape.getY1().compose().dataY == 110));
            FalseBreakoutMonitorTest.breakout(monitor);
            monitor.onDepth(true, 105, 4500, FalseBreakoutMonitorTest.OPEN, true);
            monitor.onTimestamp(BidBreakdownMonitorTest.MATURE, true); painter.refreshNow();
            assertTrue(shapes.stream().noneMatch(shape -> shape.getY1().compose().dataY == 110));
            assertTrue(shapes.stream().anyMatch(shape -> shape.getY1().compose().dataY == 105));
            monitor.setEnabled(false); painter.refreshNow(); assertTrue(shapes.isEmpty());
            screen.dispose(); screen.dispose(); assertEquals(1, disposals.get());
        } finally { painter.shutdown(); }
        assertEquals(1, disposals.get());
    }
}
