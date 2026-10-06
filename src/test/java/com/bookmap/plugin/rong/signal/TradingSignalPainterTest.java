package com.bookmap.plugin.rong.signal;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.signal.CompositionModelsTest.event;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.patterns.*;

class TradingSignalPainterTest {
    @Test void shortBadgeExplainsTriggerBandAndAppliedSizeWithoutScore() {
        SignalComposer c = new SignalComposer("TEST", 1, SignalComposerConfig.defaults(), () -> 1000);
        c.onPatternEvent(event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 100, "b"));
        TradingSignal signal = c.onPatternEvent(event(PatternEventType.OFFER_REJECTION, 60000, 5120, 200, "o")).signals.get(0);
        String text = String.join("\n", TradingSignalPainter.badgeLines(signal));
        assertTrue(text.contains("SHORT")); assertTrue(text.contains("3K")); assertTrue(text.contains("EXCEPTIONAL"));
        assertTrue(text.contains("normal 5K / applied 3K")); assertTrue(text.contains("60K")); assertFalse(text.toLowerCase().contains("score"));
        assertEquals(100, signal.trigger.eventTimeNs); assertEquals(200, TradingSignalPainter.anchorTimeNs(signal));
        java.awt.image.BufferedImage image = TradingSignalPainter.renderBadge(signal);
        assertTrue(image.getWidth() > 100); assertTrue(image.getHeight() > 20); assertNotEquals(0, image.getRGB(10, 10));
    }
    @Test void longBadgeAndLaterEvidencePreserveOriginalThresholds() {
        SignalComposer c = new SignalComposer("TEST", 1, SignalComposerConfig.defaults(), () -> 1000);
        TradingSignal original = c.onPatternEvent(event(PatternEventType.BID_STEP_UP, 5000, 5105, 100, "b")).signals.get(0);
        assertTrue(TradingSignalPainter.badgeLines(original).get(0).contains("LONG"));
        TradingSignal revised = c.onPatternEvent(event(PatternEventType.OFFER_BREAKOUT, 60000, 5120, 200, "o")).signals.get(0);
        assertTrue(TradingSignalPainter.badgeLines(revised).get(1).contains("normal 5K / applied 5K"));
        assertTrue(TradingSignalPainter.badgeLines(revised).get(2).contains("updated evidence EXCEPTIONAL"));
        assertEquals(100, TradingSignalPainter.anchorTimeNs(revised)); assertTrue(TradingSignalPainter.renderBadge(revised).getWidth() > 100);
    }
}
