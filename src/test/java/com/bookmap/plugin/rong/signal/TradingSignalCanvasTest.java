package com.bookmap.plugin.rong.signal;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.signal.CompositionModelsTest.event;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.IndicatorConfig;
import com.bookmap.plugin.rong.patterns.*;
import velox.api.layer1.layers.strategies.interfaces.*;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.*;

class TradingSignalCanvasTest {
    static class Canvas {
        final List<CanvasIcon> shapes = new CopyOnWriteArrayList<>();
        final AtomicInteger disposals = new AtomicInteger();
        List<CanvasIcon> markers() {
            return shapes.stream().filter(icon -> icon.getX1().compose().base == CompositeCoordinateBase.DATA_ZERO)
                    .collect(java.util.stream.Collectors.toList());
        }
        volatile Thread publishingThread;
        final ScreenSpaceCanvas proxy = (ScreenSpaceCanvas)Proxy.newProxyInstance(ScreenSpaceCanvas.class.getClassLoader(),
                new Class<?>[] {ScreenSpaceCanvas.class}, (object, method, args) -> {
                    if (method.getName().equals("addShape") || method.getName().equals("removeShape")) {
                        assertNotEquals(publishingThread, Thread.currentThread(), "Canvas rendering ran on the publishing callback thread");
                        if (method.getName().equals("addShape")) shapes.add((CanvasIcon)args[0]); else shapes.remove(args[0]);
                    }
                    if (method.getName().equals("dispose")) disposals.incrementAndGet();
                    return null;
                });
        final ScreenSpaceCanvasFactory factory = (ScreenSpaceCanvasFactory)Proxy.newProxyInstance(ScreenSpaceCanvasFactory.class.getClassLoader(),
                new Class<?>[] {ScreenSpaceCanvasFactory.class}, (object, method, args) -> proxy);
    }
    @Test void validationAnchorSameIdRevisionExpiryAndTeardownUseImmutableStore() {
        AtomicLong clock = new AtomicLong(1000); TradingSignalStore store = new TradingSignalStore(clock::get);
        IndicatorConfig config = new IndicatorConfig(); config.setEnabled(IndicatorConfig.SIGNAL_COMPOSER, true);
        TradingSignalPainter painter = new TradingSignalPainter(store, config); Canvas canvas = new Canvas();
        try {
            painter.registerInstrument("TEST"); painter.createScreenSpacePainter("TEST", "signalComposer_TEST", canvas.factory);
            SignalComposer c = new SignalComposer("TEST", 1, SignalComposerConfig.defaults(), clock::get);
            c.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 100, "b"));
            CompositionUpdate validation = c.onPatternEvent(event(PatternEventType.OFFER_HOLD, 60000, 5120, 200, "o"));
            canvas.publishingThread = Thread.currentThread(); store.publish("TEST", 1, validation); canvas.publishingThread = null;
            painter.refreshNow(); assertEquals(1, canvas.markers().size());
            CanvasIcon icon = canvas.markers().get(0);
            assertEquals(200, icon.getX1().compose().timeX); assertEquals(5105, icon.getY1().compose().dataY);
            CompositionUpdate revision = c.onPatternEvent(event(PatternEventType.OFFER_HOLD, 70000, 5120, 300, "later"));
            store.publish("TEST", 1, revision); painter.refreshNow(); assertEquals(1, canvas.markers().size());
            assertNotSame(icon, canvas.markers().get(0)); assertEquals(200, canvas.markers().get(0).getX1().compose().timeX);
            clock.set(31000); painter.refreshNow(); assertTrue(canvas.markers().isEmpty());
            painter.unregisterInstrument("TEST"); assertEquals(1, canvas.disposals.get());
            painter.shutdown(); assertEquals(1, canvas.disposals.get());
        } finally { painter.shutdown(); }
    }
    @Test void disabledAndUnknownAliasesCannotPaintAndDisposeIsIdempotent() {
        TradingSignalStore store = new TradingSignalStore(() -> 1000); IndicatorConfig config = new IndicatorConfig();
        config.setEnabled(IndicatorConfig.SIGNAL_COMPOSER, false);
        TradingSignalPainter painter = new TradingSignalPainter(store, config); Canvas canvas = new Canvas();
        try {
            painter.registerInstrument("TEST"); ScreenSpacePainter screen = painter.createScreenSpacePainter("TEST", "signalComposer_TEST", canvas.factory);
            SignalComposer c = new SignalComposer("TEST", 1, SignalComposerConfig.defaults(), () -> 1000);
            store.publish("TEST", 1, c.onPatternEvent(event(PatternEventType.BID_STEP_UP, 5000, 5105, 100, "b")));
            painter.refreshNow(); assertTrue(canvas.shapes.isEmpty());
            config.setEnabled(IndicatorConfig.SIGNAL_COMPOSER, true); painter.refreshNow(); assertEquals(1, canvas.shapes.size());
            config.setEnabled(IndicatorConfig.SIGNAL_COMPOSER, false); painter.refreshNow(); assertTrue(canvas.shapes.isEmpty());
            screen.dispose(); screen.dispose(); assertEquals(1, canvas.disposals.get());
        } finally { painter.shutdown(); }
    }
}
