package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.patterns.FalseBreakoutMonitorTest.OPEN;
import org.junit.jupiter.api.Test;

class FalseBreakdownMonitorTest {
    private static final long MATURE = OPEN + FalseBreakdownMonitor.BID_MIN_DURATION_NS + 1;
    static FalseBreakdownMonitor started() {
        FalseBreakdownMonitor m = new FalseBreakdownMonitor(); m.setEnabled(true);
        m.onTrade(110, 1, true, OPEN, true); return m;
    }
    static FalseBreakdownMonitor matureBids() {
        FalseBreakdownMonitor m = started();
        for (int tick : new int[]{107, 105, 103, 101}) m.onDepth(true, tick, 6000, OPEN);
        m.onTimestamp(MATURE); return m;
    }

    @Test void clearingRemovesBidLabelAndImmediatelyMonitorsNextBidWithoutBreakdown() {
        FalseBreakdownMonitor m = matureBids();
        assertEquals(107, m.snapshot().highlightedTick); assertEquals(1, m.snapshot().highlightedNumber);
        m.onDepth(true, 107, 0, MATURE);
        assertEquals(1, m.snapshot().entries.size());
        assertEquals(FalseBreakdownMonitor.Phase.CLEARED, m.snapshot().entries.get(0).phase);
        assertEquals(105, m.snapshot().highlightedTick); assertEquals(2, m.snapshot().highlightedNumber);
        m.onTrade(106, 10, false, MATURE + 1, true);
        m.onBbo(106, 107, MATURE + 2, true); // Ask equal to cleared price is not below.
        assertEquals(FalseBreakdownMonitor.Phase.CLEARED, m.snapshot().entries.get(0).phase);
        m.onBbo(105, 106, MATURE + 3, true);
        assertEquals(FalseBreakdownMonitor.Phase.BREAKDOWN, m.snapshot().entries.get(0).phase);
        assertEquals(MATURE + 3, m.snapshot().entries.get(0).breakdownTimeNs);
        assertEquals(105, m.snapshot().highlightedTick);
    }

    @Test void longReclaimRequiresBidStrictlyAboveAndBreakdownFirst() {
        FalseBreakdownMonitor m = matureBids(); m.onDepth(true, 107, 0, MATURE);
        m.onBbo(108, 109, MATURE + 1, true); // No preceding breakdown.
        assertEquals(FalseBreakdownMonitor.Phase.CLEARED, m.snapshot().entries.get(0).phase);
        m.onBbo(105, 106, MATURE + 2, true);
        m.onBbo(106, 108, MATURE + 3, true); // Ask above is insufficient.
        m.onTrade(109, 100, true, MATURE + 4, true); // Last trade above is insufficient.
        m.onBbo(107, 108, MATURE + 5, true); // Bid equal is insufficient.
        assertEquals(FalseBreakdownMonitor.Phase.BREAKDOWN, m.snapshot().entries.get(0).phase);
        m.onBbo(108, 109, MATURE + 6, true);
        assertEquals(FalseBreakdownMonitor.Phase.RECLAIMED, m.snapshot().entries.get(0).phase);
        assertEquals(MATURE + 6, m.snapshot().entries.get(0).reclaimTimeNs);
        m.onBbo(105, 106, MATURE + 7, true); m.onBbo(108, 109, MATURE + 8, true);
        assertEquals(MATURE + 6, m.snapshot().entries.get(0).reclaimTimeNs);
    }

    @Test void successiveQuoteCallbacksMayShareTheSameMarketTimestamp() {
        FalseBreakdownMonitor m = matureBids(); m.onDepth(true, 107, 0, MATURE);
        m.onBbo(105, 106, MATURE, true);
        assertEquals(FalseBreakdownMonitor.Phase.BREAKDOWN, m.snapshot().entries.get(0).phase);
        m.onBbo(108, 109, MATURE, true);
        assertEquals(FalseBreakdownMonitor.Phase.RECLAIMED, m.snapshot().entries.get(0).phase);
    }

    @Test void residualSmallBidCannotKeepFirstBidLabelStuckAfterAskTradesBelow() {
        FalseBreakdownMonitor m = matureBids();
        m.onDepth(true, 107, 200, MATURE);
        m.onBbo(105, 106, MATURE + 1, true);
        assertEquals(1, m.snapshot().entries.size());
        assertEquals(FalseBreakdownMonitor.Phase.BREAKDOWN, m.snapshot().entries.get(0).phase);
        assertEquals(105, m.snapshot().highlightedTick);
        assertEquals(2, m.snapshot().highlightedNumber);
    }

    @Test void quoteBelowArrivingBeforeDepthClearStillConfirmsWithoutAnotherQuoteChange() {
        FalseBreakdownMonitor m = matureBids();
        m.onBbo(105, 106, MATURE, true);
        m.onDepth(true, 107, 0, MATURE + 1);
        assertEquals(FalseBreakdownMonitor.Phase.BREAKDOWN, m.snapshot().entries.get(0).phase);
        assertEquals(MATURE + 1, m.snapshot().entries.get(0).breakdownTimeNs);
    }

    @Test void threeClearsLimitTrackingButPendingQuotesStillConfirmAndReclaim() {
        FalseBreakdownMonitor m = matureBids();
        m.onDepth(true, 107, 0, MATURE); m.onDepth(true, 105, 0, MATURE + 1);
        assertEquals(103, m.snapshot().highlightedTick); assertEquals(3, m.snapshot().highlightedNumber);
        m.onDepth(true, 103, 0, MATURE + 2);
        assertNull(m.snapshot().highlightedTick); assertEquals(3, m.snapshot().entries.size());
        m.onDepth(true, 101, 0, MATURE + 3);
        assertEquals(3, m.snapshot().entries.size());
        m.onBbo(100, 101, MATURE + 4, true);
        for (FalseBreakdownMonitor.Level level : m.snapshot().entries) assertEquals(FalseBreakdownMonitor.Phase.BREAKDOWN, level.phase);
        m.onBbo(108, 109, MATURE + 5, true);
        for (FalseBreakdownMonitor.Level level : m.snapshot().entries) assertEquals(FalseBreakdownMonitor.Phase.RECLAIMED, level.phase);
    }

    @Test void residualBidClearsLargeWallButOnlyAskBelowConfirmsBreakdown() {
        FalseBreakdownMonitor m = matureBids();
        m.onDepth(true, 107, 1000, MATURE);
        assertEquals(105, m.snapshot().highlightedTick);
        assertEquals(FalseBreakdownMonitor.Phase.CLEARED, m.snapshot().entries.get(0).phase);
        m.onTrade(106, 500, false, MATURE + 1, true);
        m.onBbo(105, 106, MATURE + 2, true);
        assertEquals(FalseBreakdownMonitor.Phase.BREAKDOWN, m.snapshot().entries.get(0).phase);
        m.onDepth(true, 107, 0, MATURE + 3);
        assertEquals(FalseBreakdownMonitor.Phase.BREAKDOWN, m.snapshot().entries.get(0).phase);
        m.onBbo(105, 106, MATURE + 4, true);
        assertEquals(FalseBreakdownMonitor.Phase.BREAKDOWN, m.snapshot().entries.get(0).phase);
    }

    @Test void sizeDurationAndDistinctPricesAreRequired() {
        FalseBreakdownMonitor brief = started(); brief.onDepth(true, 107, 6000, OPEN);
        brief.onDepth(true, 107, 0, OPEN + FalseBreakdownMonitor.BID_MIN_DURATION_NS);
        assertTrue(brief.snapshot().entries.isEmpty());
        FalseBreakdownMonitor m = matureBids(); m.onDepth(true, 107, 0, MATURE);
        m.onDepth(true, 107, 9000, MATURE + 1); m.onTimestamp(MATURE + 2_000_000_000L);
        assertEquals(105, m.snapshot().highlightedTick);
        m.onDepth(true, 107, 0, MATURE + 2_000_000_001L);
        assertEquals(1, m.snapshot().entries.size());
    }

    @Test void disabledPreSnapshotInvalidQuotesAndDailyResetCannotGenerateEvents() {
        FalseBreakdownMonitor m = matureBids();
        m.onDepth(true, 107, 0, MATURE, false); assertTrue(m.snapshot().entries.isEmpty());
        m.onDepth(true, 105, 0, MATURE + 1, true);
        m.onBbo(100, 101, MATURE + 2, false);
        m.onBbo(105, 104, MATURE + 3, true); // Crossed quote.
        m.onBbo(0, 104, MATURE + 4, true);
        assertEquals(FalseBreakdownMonitor.Phase.CLEARED, m.snapshot().entries.get(0).phase);
        m.setEnabled(false); m.onBbo(100, 101, MATURE + 5, true); m.setEnabled(true);
        assertEquals(FalseBreakdownMonitor.Phase.CLEARED, m.snapshot().entries.get(0).phase);
        m.onBbo(100, 101, MATURE + 6, true);
        assertEquals(FalseBreakdownMonitor.Phase.BREAKDOWN, m.snapshot().entries.get(0).phase);
        m.onTimestamp(MATURE + 86_400_000_000_000L);
        assertTrue(m.snapshot().entries.isEmpty());
        m.onTimestamp(OPEN); assertNull(m.snapshot().highlightedTick);
    }
}
