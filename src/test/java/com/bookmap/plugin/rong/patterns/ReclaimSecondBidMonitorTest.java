package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.patterns.FalseBreakoutMonitorTest.OPEN;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ReclaimSecondBidMonitorTest {
    static final long MATURE = OPEN + ReclaimSecondBidMonitor.BID_MIN_DURATION_NS + 1;
    static ReclaimSecondBidMonitor mature() {
        ReclaimSecondBidMonitor m = new ReclaimSecondBidMonitor(); m.setEnabled(true);
        m.onBbo(110, 111, OPEN, true);
        for (int price : new int[]{107, 105, 103, 101, 99}) m.onDepth(true, price, 6000, OPEN, true);
        m.onTimestamp(MATURE); return m;
    }
    static java.util.List<Integer> prices(ReclaimSecondBidMonitor m) {
        return m.snapshot().fills.stream().map(fill -> fill.bidTick).collect(Collectors.toList());
    }

    @Test void partialFillCountsImmediatelyAndThirdDistinctFillRollsOldestOut() {
        ReclaimSecondBidMonitor m = mature(); assertEquals(107, m.snapshot().nextBidTick);
        m.onTrade(107, 10, false, MATURE, true);
        assertEquals(java.util.List.of(107), prices(m)); assertEquals(105, m.snapshot().nextBidTick);
        m.onTrade(105, 20, false, MATURE + 1, true);
        assertEquals(java.util.List.of(107, 105), prices(m)); assertEquals(103, m.snapshot().nextBidTick);
        m.onTrade(103, 30, false, MATURE + 2, true);
        assertEquals(java.util.List.of(105, 103), prices(m)); assertEquals(101, m.snapshot().nextBidTick);
        m.onTrade(101, 40, false, MATURE + 3, true); m.onTrade(99, 50, false, MATURE + 4, true);
        assertEquals(java.util.List.of(101, 99), prices(m)); // No three-bid/session cap.
        m.onBbo(110, 111, MATURE + 5, true);
        assertEquals(107, m.snapshot().nextBidTick); // A rolled-out refilled level can be tracked again.
    }

    @Test void longRequiresLevelOneBidAboveBothAndOnlySignalsOnceForEachPair() {
        ReclaimSecondBidMonitor m = mature(); m.onTrade(107, 1, false, MATURE, true);
        m.onBbo(110, 111, MATURE + 1, true); assertTrue(m.snapshot().signals.isEmpty());
        m.onTrade(105, 1, false, MATURE + 2, true);
        m.onTrade(110, 1, true, MATURE + 3, true); assertTrue(m.snapshot().signals.isEmpty());
        m.onBbo(106, 110, MATURE + 4, true); assertTrue(m.snapshot().signals.isEmpty());
        m.onBbo(107, 108, MATURE + 5, true); assertTrue(m.snapshot().signals.isEmpty());
        m.onBbo(108, 109, MATURE + 6, true); assertEquals(1, m.snapshot().signals.size());
        assertEquals(107, m.snapshot().signals.get(0).olderBidTick);
        assertEquals(105, m.snapshot().signals.get(0).newerBidTick);
        m.onBbo(104, 105, MATURE + 7, true); m.onBbo(110, 111, MATURE + 8, true);
        assertEquals(1, m.snapshot().signals.size());
        m.onTrade(103, 1, false, MATURE + 9, true); m.onBbo(106, 107, MATURE + 10, true);
        assertEquals(2, m.snapshot().signals.size());
    }

    @Test void repeatedFillsAtTrackedLevelAccumulateWithoutReorderingOrDuplicating() {
        ReclaimSecondBidMonitor m = mature(); m.onTrade(107, 10, false, MATURE, true);
        m.onTrade(105, 20, false, MATURE + 1, true);
        m.onTrade(107, 30, false, MATURE + 2, true);
        assertEquals(java.util.List.of(107, 105), prices(m));
        assertEquals(40, m.snapshot().fills.get(0).filledSize);
        assertEquals(MATURE, m.snapshot().fills.get(0).firstFillTimeNs);
    }

    @Test void cancellationsBuysAndBriefWallsNeverBecomeFills() {
        ReclaimSecondBidMonitor m = mature(); m.onTrade(107, 100, true, MATURE, true);
        m.onDepth(true, 107, 0, MATURE, true);
        assertTrue(m.snapshot().fills.isEmpty());
        m.onTrade(107, 10, false, MATURE + 300_000_000L, true);
        assertTrue(m.snapshot().fills.isEmpty());
        m.onDepth(true, 108, 6000, MATURE + 300_000_000L, true);
        m.onTrade(108, 10, false, MATURE + 400_000_000L, true);
        assertTrue(m.snapshot().fills.isEmpty());
    }

    @Test void depthFirstConsumptionStillAttributesTheNextActualFill() {
        ReclaimSecondBidMonitor m = mature(); m.onDepth(true, 107, 4000, MATURE, true);
        assertTrue(m.snapshot().fills.isEmpty());
        m.onTrade(107, 2000, false, MATURE + 1, true);
        assertEquals(java.util.List.of(107), prices(m));
        m.onDepth(true, 107, 0, MATURE + 2, true);
        m.onTrade(107, 4000, false, MATURE + 3, true);
        assertEquals(6000, m.snapshot().fills.get(0).filledSize);
    }

    @Test void snapshotReadinessDisableAndReplayOrDailyResetAreRespected() {
        ReclaimSecondBidMonitor m = mature(); m.onTrade(107, 10, false, MATURE, false);
        assertTrue(m.snapshot().fills.isEmpty());
        m.setEnabled(false); m.onTrade(107, 10, false, MATURE + 1, true); m.setEnabled(true);
        assertTrue(m.snapshot().fills.isEmpty());
        m.onTrade(107, 10, false, MATURE + 2, true);
        m.onTimestamp(MATURE + 86_400_000_000_000L); assertTrue(m.snapshot().fills.isEmpty());
        m.onTimestamp(OPEN); assertNull(m.snapshot().nextBidTick);
    }
}
