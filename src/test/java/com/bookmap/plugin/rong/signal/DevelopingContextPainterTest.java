package com.bookmap.plugin.rong.signal;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.signal.CompositionModelsTest.event;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.IndicatorConfig;
import com.bookmap.plugin.rong.patterns.*;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CompositeCoordinateBase;

class DevelopingContextPainterTest {
    @Test void offerOnlyShowsDistinctWaitingAreaAndPauseCannotExpireMarketContext() {
        AtomicLong receipt = new AtomicLong(1000); TradingSignalStore store = new TradingSignalStore(receipt::get);
        IndicatorConfig config = new IndicatorConfig(); config.setEnabled(IndicatorConfig.SIGNAL_COMPOSER, true);
        TradingSignalPainter painter = new TradingSignalPainter(store, config); TradingSignalCanvasTest.Canvas canvas = new TradingSignalCanvasTest.Canvas();
        try {
            painter.registerInstrument("TEST"); painter.createScreenSpacePainter("TEST", "signalComposer_TEST", canvas.factory);
            SignalComposer c = new SignalComposer("TEST", 1, SignalComposerConfig.defaults(), receipt::get);
            store.publish("TEST", 1, c.onPatternEvent(event(PatternEventType.OFFER_BOUNCE, 60000, 5120, 100, "short")));
            store.publish("TEST", 1, c.onPatternEvent(event(PatternEventType.OFFER_BREAKOUT, 5000, 5120, 100, "long")));
            java.util.List<String> initial = TradingSignalPainter.contextLines(store.snapshot("TEST"));
            String text = String.join("\n", initial);
            assertTrue(text.contains("SHORT")); assertTrue(text.contains("LONG")); assertTrue(text.contains("Waiting for BID_FAIL >= 3K"));
            assertTrue(text.contains("Waiting for BID_HOLD >= 5K")); assertTrue(text.contains("51.2"));
            painter.refreshNow(); assertTrue(canvas.markers().isEmpty()); assertEquals(1, canvas.shapes.size());
            assertEquals(CompositeCoordinateBase.PIXEL_ZERO, canvas.shapes.get(0).getX1().compose().base);
            receipt.set(1_000_000); painter.refreshNow(); assertEquals(initial, TradingSignalPainter.contextLines(store.snapshot("TEST")));
            store.publish("TEST", 1, c.onMarketTime(300_000_000_101L)); painter.refreshNow(); assertTrue(canvas.shapes.isEmpty());
            store.clear("TEST", 2); painter.refreshNow(); assertTrue(canvas.shapes.isEmpty());
        } finally { painter.shutdown(); }
    }
}
