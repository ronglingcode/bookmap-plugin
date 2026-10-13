package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.patterns.FalseBreakoutMonitorTest.OPEN;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import velox.api.layer1.layers.strategies.interfaces.*;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.*;

class FalseBreakdownPainterTest {
    @Test void clearRemovesLiveLabelAndAskBreakdownAndBidReclaimLeaveSeparateMarkers() {
        FalseBreakdownMonitor monitor = new FalseBreakdownMonitor(); monitor.setEnabled(true);
        monitor.onTrade(110, 1, true, OPEN, true); monitor.onDepth(true, 105, 6000, OPEN);
        List<CanvasIcon> shapes = new CopyOnWriteArrayList<>();
        ScreenSpaceCanvas canvas = (ScreenSpaceCanvas) Proxy.newProxyInstance(ScreenSpaceCanvas.class.getClassLoader(), new Class<?>[]{ScreenSpaceCanvas.class}, (object, method, args) -> {
            if (method.getName().equals("addShape")) shapes.add((CanvasIcon) args[0]);
            if (method.getName().equals("removeShape")) shapes.remove(args[0]); return null;
        });
        ScreenSpaceCanvasFactory factory = (ScreenSpaceCanvasFactory) Proxy.newProxyInstance(ScreenSpaceCanvasFactory.class.getClassLoader(), new Class<?>[]{ScreenSpaceCanvasFactory.class}, (object, method, args) -> canvas);
        FalseBreakdownPainter painter = new FalseBreakdownPainter(monitor);
        try {
            painter.createScreenSpacePainter("TEST", "falseBreakdown_TEST", factory);
            long time = OPEN + WallPatternMonitor.BID_MIN_DURATION_NS + 1;
            monitor.onTimestamp(time); painter.refreshNow(); assertEquals(2, shapes.size());
            monitor.onDepth(true, 105, 0, time); painter.refreshNow();
            assertEquals(1, shapes.size()); // Caption only: clearing does not leave a bid marker.
            monitor.onBbo(103, 104, time + 1, true); painter.refreshNow();
            CanvasIcon marker = shapes.stream().filter(shape -> shape.getX1().compose().base == CompositeCoordinateBase.DATA_ZERO).findFirst().orElseThrow();
            assertEquals(time + 1, marker.getX1().compose().timeX);
            assertEquals(105, marker.getY1().compose().dataY);
            monitor.onBbo(106, 107, time + 2, true); painter.refreshNow();
            assertEquals(2, shapes.stream().filter(shape -> shape.getX1().compose().base == CompositeCoordinateBase.DATA_ZERO).count());
            assertTrue(shapes.stream().anyMatch(shape -> shape.getX1().compose().base == CompositeCoordinateBase.DATA_ZERO
                    && shape.getX1().compose().timeX == time + 1 && shape.getY1().compose().dataY == 105));
            monitor.setEnabled(false); painter.refreshNow(); assertTrue(shapes.isEmpty());
        } finally { painter.shutdown(); }
    }
}
