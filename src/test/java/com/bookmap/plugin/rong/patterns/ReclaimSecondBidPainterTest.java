package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import velox.api.layer1.layers.strategies.interfaces.*;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.*;

class ReclaimSecondBidPainterTest {
    @Test void rollingFillLinesReplaceOldestAndQuotesAboveBothNeverPaintEntryMarkers() {
        ReclaimSecondBidMonitor monitor = ReclaimSecondBidMonitorTest.mature();
        List<CanvasIcon> shapes = new CopyOnWriteArrayList<>();
        ScreenSpaceCanvas canvas = (ScreenSpaceCanvas) Proxy.newProxyInstance(ScreenSpaceCanvas.class.getClassLoader(), new Class<?>[]{ScreenSpaceCanvas.class}, (object, method, args) -> {
            if (method.getName().equals("addShape")) shapes.add((CanvasIcon) args[0]);
            if (method.getName().equals("removeShape")) shapes.remove(args[0]); return null;
        });
        ScreenSpaceCanvasFactory factory = (ScreenSpaceCanvasFactory) Proxy.newProxyInstance(ScreenSpaceCanvasFactory.class.getClassLoader(), new Class<?>[]{ScreenSpaceCanvasFactory.class}, (object, method, args) -> canvas);
        ReclaimSecondBidPainter painter = new ReclaimSecondBidPainter(monitor);
        try {
            painter.createScreenSpacePainter("TEST", "reclaim2ndBid_TEST", factory);
            long now = ReclaimSecondBidMonitorTest.MATURE;
            monitor.onTrade(107, 1, false, now, true); monitor.onTrade(105, 1, false, now + 1, true);
            monitor.onTrade(103, 1, false, now + 2, true); painter.refreshNow();
            assertFalse(shapes.stream().anyMatch(shape -> shape.getY1().compose().base == CompositeCoordinateBase.DATA_ZERO && shape.getY1().compose().dataY == 107));
            assertTrue(shapes.stream().anyMatch(shape -> shape.getY1().compose().dataY == 105));
            assertTrue(shapes.stream().anyMatch(shape -> shape.getY1().compose().dataY == 103));
            monitor.onBbo(106, 107, now + 3, true); painter.refreshNow();
            assertTrue(shapes.stream().noneMatch(shape -> shape.getX1().compose().base == CompositeCoordinateBase.DATA_ZERO));
            assertEquals(4, shapes.size()); // Caption, two fill lines, and next bid.
            monitor.setEnabled(false); painter.refreshNow(); assertTrue(shapes.isEmpty());
        } finally { painter.shutdown(); }
    }
}
