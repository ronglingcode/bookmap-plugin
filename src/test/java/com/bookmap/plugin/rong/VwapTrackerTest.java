package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class VwapTrackerTest {

    @Test
    void storesViteAppUpdatesWithoutComputingFromTrades() {
        VwapTracker tracker = new VwapTracker("AAPL");

        assertTrue(tracker.applyUpdate(update("AAPL", 100.25, 1_000, 1_100)));
        assertTrue(tracker.applyUpdate(update("AAPL", 100.50, 2_000, 2_100)));

        assertEquals(100.50, tracker.getCurrentVwap(), 0.00001);
        List<VwapTracker.VwapPoint> points = tracker.drainPendingIndicatorPoints();
        assertEquals(2, points.size());
        assertEquals(1_000_000_000L, points.get(0).getTimestampNs());
        assertEquals(100.25, points.get(0).getValue(), 0.00001);
        assertEquals(2_000_000_000L, points.get(1).getTimestampNs());
        assertEquals(100.50, points.get(1).getValue(), 0.00001);
    }

    @Test
    void rejectsWrongSymbolDuplicateAndOlderUpdates() {
        VwapTracker tracker = new VwapTracker("AAPL");
        assertTrue(tracker.applyUpdate(update("AAPL", 100.25, 2_000, 2_100)));

        assertFalse(tracker.applyUpdate(update("MSFT", 300.00, 3_000, 3_100)));
        assertFalse(tracker.applyUpdate(update("AAPL", 99.00, 1_000, 3_100)));
        assertFalse(tracker.applyUpdate(update("AAPL", 101.00, 2_000, 2_100)));
        assertEquals(100.25, tracker.getCurrentVwap(), 0.00001);
    }

    @Test
    void newerCorrectionAtTheSameEffectiveTimeIsAccepted() {
        VwapTracker tracker = new VwapTracker("AAPL");
        tracker.applyUpdate(update("AAPL", 100.25, 2_000, 2_100));

        assertTrue(tracker.applyUpdate(update("AAPL", 100.30, 2_000, 2_200)));
        assertEquals(100.30, tracker.getCurrentVwap(), 0.00001);
    }

    @Test
    void canRepeatCurrentAuthoritativeValueWhenIndicatorIsEnabled() {
        VwapTracker tracker = new VwapTracker("AAPL");
        tracker.applyUpdate(update("AAPL", 100.25, 2_000, 2_100));
        tracker.drainPendingIndicatorPoints();

        tracker.requestCurrentPoint(5_000_000_000L);

        List<VwapTracker.VwapPoint> points = tracker.drainPendingIndicatorPoints();
        assertEquals(1, points.size());
        assertEquals(5_000_000_000L, points.get(0).getTimestampNs());
        assertEquals(100.25, points.get(0).getValue(), 0.00001);
    }

    private static VwapUpdateDefinition update(
            String symbol,
            double vwap,
            long effectiveTimeMs,
            long sentAtMs) {
        return new VwapUpdateDefinition(symbol, vwap, effectiveTimeMs, sentAtMs);
    }
}
