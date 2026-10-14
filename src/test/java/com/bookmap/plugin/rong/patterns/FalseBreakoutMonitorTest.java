package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FalseBreakoutMonitorTest {
    @Test void offerUsesPremarketThresholdWhileBidAlwaysUsesFiveThousand() {
        for (int threshold : new int[]{2000, 20000}) {
            java.util.concurrent.atomic.AtomicInteger offerThreshold = new java.util.concurrent.atomic.AtomicInteger(threshold);
            FalseBreakoutMonitor monitor = new FalseBreakoutMonitor(offerThreshold::get);
            monitor.setEnabled(true);
            monitor.onTrade(100, 1, true, OPEN, true);
            monitor.onDepth(false, 130, threshold, OPEN);
            assertNull(monitor.snapshot().highlightedTick);
            monitor.onDepth(false, 130, threshold + 1, OPEN);
            assertEquals(130, monitor.snapshot().highlightedTick);
            monitor.onDepth(true, 105, 5000, OPEN);
            monitor.onDepth(true, 104, 6000, OPEN);
            long mature = OPEN + FalseBreakoutMonitor.BID_MIN_DURATION_NS + 1;
            monitor.onTrade(130, threshold + 1, true, mature, true);
            assertEquals(FalseBreakoutMonitor.Phase.BID_BREAKDOWN, monitor.snapshot().phase);
            assertEquals(104, monitor.snapshot().highlightedTick);
            offerThreshold.set(50000);
            monitor.onTimestamp(mature + 1);
            assertEquals(104, monitor.snapshot().highlightedTick);
            monitor.onTrade(103, 1, false, mature + 2, true);
            assertEquals(1, monitor.snapshot().entries.size());
            assertEquals(104, monitor.snapshot().entries.get(0).bidTick);
        }
    }
    static final long OPEN = Instant.parse("2026-10-06T13:30:00Z").getEpochSecond() * 1_000_000_000L;
    static FalseBreakoutMonitor started() {
        FalseBreakoutMonitor monitor = new FalseBreakoutMonitor(); monitor.setEnabled(true);
        monitor.onTrade(100, 1, true, OPEN, true); return monitor;
    }
    static void breakout(FalseBreakoutMonitor monitor) {
        monitor.onDepth(false, 110, 6000, OPEN);
        monitor.onTrade(110, 6000, true, OPEN, true);
        assertEquals(FalseBreakoutMonitor.Phase.BID_BREAKDOWN, monitor.snapshot().phase);
    }

    @Test void marketOpenSelectsNearestOfferStrictlyAbovePriceAndThreshold() {
        FalseBreakoutMonitor m = new FalseBreakoutMonitor(); m.setEnabled(true);
        m.onDepth(false, 105, 5000, OPEN - 1);
        m.onDepth(false, 110, 6000, OPEN - 1);
        m.onDepth(false, 100, 7000, OPEN - 1);
        m.onTrade(100, 1, true, OPEN - 1, true);
        assertNull(m.snapshot().highlightedTick);
        m.onTimestamp(OPEN);
        assertEquals(110, m.snapshot().highlightedTick);
        m.onDepth(false, 107, 7000, OPEN);
        assertEquals(107, m.snapshot().highlightedTick);
        m.onDepth(false, 107, 0, OPEN);
        assertEquals(110, m.snapshot().highlightedTick);
    }

    @Test void cancellationAloneDoesNotCompleteBreakoutAndDepthMayArriveBeforeTrade() {
        FalseBreakoutMonitor m = started(); m.onDepth(false, 110, 6000, OPEN);
        m.onDepth(false, 110, 0, OPEN);
        assertEquals(FalseBreakoutMonitor.Phase.BREAKOUT, m.snapshot().phase);
        m.onTrade(110, 6000, true, OPEN, true);
        assertEquals(FalseBreakoutMonitor.Phase.BID_BREAKDOWN, m.snapshot().phase);

        m = started(); m.onDepth(false, 110, 6000, OPEN); m.onDepth(false, 110, 0, OPEN);
        m.onTrade(111, 6000, true, OPEN, true);
        assertEquals(FalseBreakoutMonitor.Phase.BREAKOUT, m.snapshot().phase);
    }

    @Test void offerFillingAccumulatesOnlyExecutedBuysAndNewCloserOffersReplaceTarget() {
        FalseBreakoutMonitor m = started(); m.onDepth(false, 120, 9000, OPEN);
        m.onDepth(false, 110, 6000, OPEN);
        m.onTrade(110, 6000, false, OPEN, true);
        assertEquals(FalseBreakoutMonitor.Phase.BREAKOUT, m.snapshot().phase);
        m.onTrade(110, 2000, true, OPEN, true);
        m.onDepth(false, 110, 4000, OPEN);
        m.onTrade(110, 4000, true, OPEN, true);
        assertEquals(FalseBreakoutMonitor.Phase.BID_BREAKDOWN, m.snapshot().phase);
    }

    @Test void marksFiveDifferentBidPricesThenStopsEvenIfWallsReappear() {
        FalseBreakoutMonitor m = started(); breakout(m);
        m.onDepth(true, 108, 5000, OPEN); m.onDepth(true, 107, 6000, OPEN);
        m.onDepth(true, 105, 7000, OPEN); m.onDepth(true, 103, 8000, OPEN);
        m.onDepth(true, 101, 9000, OPEN); m.onDepth(true, 99, 10000, OPEN);
        long now = OPEN + FalseBreakoutMonitor.BID_MIN_DURATION_NS + 1;
        m.onTimestamp(now);
        assertEquals(107, m.snapshot().highlightedTick);
        m.onTrade(107, 1, false, now, true);
        assertTrue(m.snapshot().entries.isEmpty());
        m.onDepth(true, 107, 0, now); // Depletion can precede the price crossing.
        m.onTrade(106, 1, false, now, true);
        assertEquals(1, m.snapshot().entries.size());
        assertEquals(107, m.snapshot().entries.get(0).bidTick);
        assertEquals(105, m.snapshot().highlightedTick);
        m.onTrade(110, 1, true, now, true); m.onDepth(true, 107, 9000, now);
        assertEquals(105, m.snapshot().highlightedTick); // The same bid price never counts twice.
        m.onTrade(104, 1, false, now, true);
        assertEquals(2, m.snapshot().entries.size());
        m.onTrade(102, 1, false, now, true);
        assertEquals(FalseBreakoutMonitor.Phase.BID_BREAKDOWN, m.snapshot().phase);
        assertEquals(3, m.snapshot().entries.size()); assertEquals(101, m.snapshot().highlightedTick);
        m.onTrade(100, 1, false, now, true);
        assertEquals(4, m.snapshot().entries.size());
        m.onTrade(98, 1, false, now, true);
        assertEquals(FalseBreakoutMonitor.Phase.COMPLETE, m.snapshot().phase);
        assertEquals(5, m.snapshot().entries.size()); assertNull(m.snapshot().highlightedTick);
        m.onDepth(true, 97, 10000, now); m.onTimestamp(now + FalseBreakoutMonitor.BID_MIN_DURATION_NS + 1);
        m.onTrade(96, 1, false, now + FalseBreakoutMonitor.BID_MIN_DURATION_NS + 1, true);
        assertEquals(5, m.snapshot().entries.size());
    }

    @Test void bidMustRemainStrictlyAboveThresholdForMoreThanOneSecond() {
        for (long elapsed : new long[]{100_000_000L, FalseBreakoutMonitor.BID_MIN_DURATION_NS}) {
            FalseBreakoutMonitor m = started(); breakout(m); m.onDepth(true, 105, 6000, OPEN);
            m.onTrade(104, 1, false, OPEN + elapsed, true);
            assertTrue(m.snapshot().entries.isEmpty());
        }
        FalseBreakoutMonitor m = started(); breakout(m); m.onDepth(true, 105, 6000, OPEN);
        m.onDepth(true, 105, 7000, OPEN + 500_000_000L); // Size updates preserve continuous duration.
        m.onTrade(104, 1, false, OPEN + FalseBreakoutMonitor.BID_MIN_DURATION_NS + 1, true);
        assertEquals(1, m.snapshot().entries.size());
    }

    @Test void dippingToThresholdRestartsBidDurationAndBriefRemovalCannotCount() {
        FalseBreakoutMonitor m = started(); breakout(m); m.onDepth(true, 105, 6000, OPEN);
        m.onDepth(true, 105, 5000, OPEN + 800_000_000L);
        m.onDepth(true, 105, 6000, OPEN + 900_000_000L);
        m.onTrade(104, 1, false, OPEN + 1_100_000_000L, true);
        assertTrue(m.snapshot().entries.isEmpty());
        m = started(); breakout(m); m.onDepth(true, 105, 6000, OPEN);
        m.onDepth(true, 105, 0, OPEN + 100_000_000L);
        m.onTrade(104, 1, false, OPEN + 1_100_000_000L, true);
        assertTrue(m.snapshot().entries.isEmpty());
    }

    @Test void canceledMatureBidCannotCreateAnEntryLongAfterItDisappears() {
        FalseBreakoutMonitor m = started(); breakout(m); m.onDepth(true, 105, 6000, OPEN);
        long now = OPEN + FalseBreakoutMonitor.BID_MIN_DURATION_NS + 1;
        m.onTimestamp(now); m.onDepth(true, 105, 0, now);
        m.onTrade(110, 1, true, now + 500_000_000L, true);
        m.onTrade(104, 1, false, now + 2_000_000_000L, true);
        assertTrue(m.snapshot().entries.isEmpty());
    }

    @Test void partiallyFilledBidRemainsArmedUntilPriceBreaksBelowIt() {
        FalseBreakoutMonitor m = started(); breakout(m);
        m.onDepth(true, 105, 6625, OPEN); m.onDepth(true, 100, 10000, OPEN);
        long now = OPEN + FalseBreakoutMonitor.BID_MIN_DURATION_NS + 1; m.onTimestamp(now);
        m.onDepth(true, 105, 4000, now);
        m.onTrade(105, 2625, false, now + 1, true);
        m.onDepth(true, 105, 1000, now + 1);
        m.onTrade(105, 3000, false, now + 2, true);
        m.onDepth(true, 105, 0, now + 2);
        assertTrue(m.snapshot().entries.isEmpty());
        m.onTrade(104, 1000, false, now + 3, true);
        assertEquals(1, m.snapshot().entries.size());
        assertEquals(105, m.snapshot().entries.get(0).bidTick);
        assertEquals(now + 3, m.snapshot().entries.get(0).timeNs);
        assertEquals(100, m.snapshot().highlightedTick);
    }

    @Test void phaseTwoUpdatesToNewClosestBidAndRequiresActualPriceCrossing() {
        FalseBreakoutMonitor m = started(); breakout(m);
        m.onDepth(true, 100, 6000, OPEN); m.onDepth(true, 105, 7000, OPEN);
        long now = OPEN + FalseBreakoutMonitor.BID_MIN_DURATION_NS + 1; m.onTimestamp(now);
        assertEquals(105, m.snapshot().highlightedTick);
        m.onTrade(104, 1, false, now, true);
        assertEquals(105, m.snapshot().entries.get(0).bidTick);
        assertEquals(100, m.snapshot().highlightedTick);
    }

    @Test void disabledPreSnapshotAndAfterHoursDoNotSignalAndNextDayResets() {
        FalseBreakoutMonitor m = started(); m.onDepth(false, 110, 6000, OPEN);
        m.onTrade(110, 6000, true, OPEN, false);
        assertEquals(FalseBreakoutMonitor.Phase.BREAKOUT, m.snapshot().phase);
        m.setEnabled(false); m.onTrade(110, 6000, true, OPEN, true);
        assertNull(m.snapshot().highlightedTick);
        assertEquals(FalseBreakoutMonitor.Phase.BREAKOUT, m.snapshot().phase);
        m.setEnabled(true); m.onTrade(100, 1, true, OPEN, true); breakout(m);
        m.onDepth(true, 105, 6000, OPEN);
        long close = OPEN + 23_400_000_000_000L;
        m.onTrade(104, 1, false, close, true);
        assertTrue(m.snapshot().entries.isEmpty()); assertNull(m.snapshot().highlightedTick);
        m.onTrade(100, 1, true, OPEN + 86_400_000_000_000L, true);
        assertEquals(FalseBreakoutMonitor.Phase.BREAKOUT, m.snapshot().phase);
        assertEquals(110, m.snapshot().highlightedTick);
    }

    @Test void replaySeekClearsBookAndEntriesAndSeparateMonitorsRemainIsolated() {
        FalseBreakoutMonitor m = started(), other = started(); breakout(m);
        m.onDepth(true, 105, 6000, OPEN);
        long now = OPEN + FalseBreakoutMonitor.BID_MIN_DURATION_NS + 1; m.onTimestamp(now);
        m.onTrade(104, 1, false, now, true);
        assertEquals(1, m.snapshot().entries.size());
        m.onTimestamp(OPEN - 1);
        assertTrue(m.snapshot().entries.isEmpty()); assertNull(m.snapshot().highlightedTick);
        assertEquals(FalseBreakoutMonitor.Phase.BREAKOUT, other.snapshot().phase);
    }
}
