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
    @Test void paintsCurrentWallAndTimeAnchoredManualEntryThenClearsOnDisableAndDispose() {
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
            monitor.onDepth(false, 110, 6000, FalseBreakoutMonitorTest.OPEN); painter.refreshNow();
            assertEquals(2, shapes.size());
            assertTrue(shapes.stream().anyMatch(shape -> shape.getY1().compose().base == CompositeCoordinateBase.DATA_ZERO && shape.getY1().compose().dataY == 110));
            FalseBreakoutMonitorTest.breakout(monitor);
            monitor.onDepth(true, 105, 6000, FalseBreakoutMonitorTest.OPEN);
            monitor.onDepth(true, 103, 6000, FalseBreakoutMonitorTest.OPEN);
            monitor.onDepth(true, 101, 6000, FalseBreakoutMonitorTest.OPEN);
            monitor.onDepth(true, 99, 6000, FalseBreakoutMonitorTest.OPEN);
            monitor.onDepth(true, 97, 6000, FalseBreakoutMonitorTest.OPEN);
            long now = FalseBreakoutMonitorTest.OPEN + FalseBreakoutMonitor.BID_MIN_DURATION_NS + 1;
            monitor.onTrade(104, 1, false, now, true); painter.refreshNow();
            CanvasIcon entry = shapes.stream().filter(shape -> shape.getX1().compose().base == CompositeCoordinateBase.DATA_ZERO).findFirst().orElseThrow();
            assertEquals(now, entry.getX1().compose().timeX);
            assertEquals(104, entry.getY1().compose().dataY);
            monitor.onTrade(102, 1, false, now + 1, true);
            monitor.onTrade(100, 1, false, now + 2, true); painter.refreshNow();
            assertEquals(FalseBreakoutMonitor.Phase.BID_BREAKDOWN, monitor.snapshot().phase);
            monitor.onTrade(98, 1, false, now + 3, true);
            monitor.onTrade(96, 1, false, now + 4, true); painter.refreshNow();
            assertEquals(FalseBreakoutMonitor.Phase.COMPLETE, monitor.snapshot().phase);
            assertEquals(5, shapes.stream().filter(shape -> shape.getX1().compose().base == CompositeCoordinateBase.DATA_ZERO).count());
            assertTrue(shapes.stream().anyMatch(shape -> shape.getX1().compose().base == CompositeCoordinateBase.DATA_ZERO
                    && shape.getX1().compose().timeX == now && shape.getY1().compose().dataY == 104));
            monitor.setEnabled(false); painter.refreshNow(); assertTrue(shapes.isEmpty());
            screen.dispose(); screen.dispose(); assertEquals(1, disposals.get());
        } finally { painter.shutdown(); }
        assertEquals(1, disposals.get());
    }
}
