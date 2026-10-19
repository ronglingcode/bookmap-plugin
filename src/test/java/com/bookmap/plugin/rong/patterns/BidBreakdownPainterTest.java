package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import velox.api.layer1.layers.strategies.interfaces.*;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.*;

class BidBreakdownPainterTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void nextLineAndEntryReclaimMarkersUseLevelAndActualEventTimes(boolean falseBreakout) {
        BidBreakdownMonitor monitor = BidBreakdownMonitorTest.monitor(falseBreakout);
        List<CanvasIcon> shapes = new CopyOnWriteArrayList<>();
        ScreenSpaceCanvas canvas = (ScreenSpaceCanvas) Proxy.newProxyInstance(ScreenSpaceCanvas.class.getClassLoader(), new Class<?>[]{ScreenSpaceCanvas.class}, (object, method, args) -> {
            if (method.getName().equals("addShape")) shapes.add((CanvasIcon) args[0]);
            if (method.getName().equals("removeShape")) shapes.remove(args[0]); return null;
        });
        ScreenSpaceCanvasFactory factory = (ScreenSpaceCanvasFactory) Proxy.newProxyInstance(ScreenSpaceCanvasFactory.class.getClassLoader(), new Class<?>[]{ScreenSpaceCanvasFactory.class}, (object, method, args) -> canvas);
        BidBreakdownPainter painter = falseBreakout ? new FalseBreakoutPainter((FalseBreakoutMonitor) monitor) : new BidBreakdownPainter(monitor);
        long base = BidBreakdownMonitorTest.BASE;
        try {
            painter.createScreenSpacePainter("TEST", "bidBreakdown_TEST", factory);
            assertEquals(2, shapes.size());
            BidBreakdownMonitorTest.breakBid(monitor, 110, BidBreakdownMonitorTest.MATURE);
            monitor.onTimestamp(base + 2_000_000_001L, true); painter.refreshNow();
            assertTrue(shapes.stream().anyMatch(shape -> shape.getX1().compose().base == CompositeCoordinateBase.DATA_ZERO
                    && shape.getX1().compose().timeX == base + 2_000_000_001L && shape.getY1().compose().dataY == 110));
            monitor.onBbo(110, 111, base + 3_000_000_000L, true); painter.refreshNow();
            assertEquals(2, shapes.stream().filter(shape -> shape.getX1().compose().base == CompositeCoordinateBase.DATA_ZERO).count());
            assertTrue(shapes.stream().anyMatch(shape -> shape.getX1().compose().timeX == base + 3_000_000_000L && shape.getY1().compose().dataY == 110));
            monitor.setEnabled(false); painter.refreshNow(); assertTrue(shapes.isEmpty());
        } finally { painter.shutdown(); }
    }
}
