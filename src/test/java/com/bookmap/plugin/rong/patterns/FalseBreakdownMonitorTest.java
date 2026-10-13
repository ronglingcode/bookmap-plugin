package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.patterns.FalseBreakoutMonitorTest.OPEN;
import org.junit.jupiter.api.Test;

class FalseBreakdownMonitorTest {
    private static FalseBreakdownMonitor started() {
        FalseBreakdownMonitor m = new FalseBreakdownMonitor(); m.setEnabled(true);
        m.onTrade(110, 1, true, OPEN, true); return m;
    }

    @Test void startsWithBidsWithoutOfferBreakoutAndStopsAtThreeDistinctBids() {
        FalseBreakdownMonitor m = started();
        m.onDepth(true, 108, 5000, OPEN);
        for (int bid : new int[]{107, 105, 103, 101}) m.onDepth(true, bid, 6000, OPEN);
        long now = OPEN + WallPatternMonitor.BID_MIN_DURATION_NS + 1;
        m.onTimestamp(now); assertEquals(107, m.snapshot().highlightedTick);
        m.onTrade(106, 1, false, now, true);
        assertEquals(107, m.snapshot().entries.get(0).bidTick);
        assertEquals(106, m.snapshot().entries.get(0).priceTick);
        m.onTrade(110, 1, true, now, true); m.onDepth(true, 107, 9000, now);
        assertEquals(105, m.snapshot().highlightedTick);
        m.onTrade(104, 1, false, now, true); m.onTrade(102, 1, false, now, true);
        assertEquals(3, m.snapshot().entries.size());
        assertEquals(WallPatternMonitor.Phase.COMPLETE, m.snapshot().phase);
        assertNull(m.snapshot().highlightedTick);
        m.onTrade(100, 1, false, now, true); assertEquals(3, m.snapshot().entries.size());
        FalseBreakoutMonitor breakout = FalseBreakoutMonitorTest.started();
        assertEquals(WallPatternMonitor.Phase.BREAKOUT, breakout.snapshot().phase);
        assertTrue(breakout.snapshot().entries.isEmpty());
    }

    @Test void usesOneSecondQualificationAndRetargetsNewClosestMatureBid() {
        FalseBreakdownMonitor m = started(); m.onDepth(true, 100, 6000, OPEN);
        m.onDepth(true, 105, 7000, OPEN + 500_000_000L);
        long now = OPEN + WallPatternMonitor.BID_MIN_DURATION_NS + 1;
        m.onTimestamp(now); assertEquals(100, m.snapshot().highlightedTick);
        m.onTimestamp(now + 500_000_000L); assertEquals(105, m.snapshot().highlightedTick);
        m.onTrade(104, 1, false, now + 500_000_001L, true);
        assertEquals(105, m.snapshot().entries.get(0).bidTick);
        FalseBreakdownMonitor brief = started(); brief.onDepth(true, 105, 6000, OPEN);
        brief.onTrade(104, 1, false, OPEN + WallPatternMonitor.BID_MIN_DURATION_NS, true);
        assertTrue(brief.snapshot().entries.isEmpty());
    }

    @Test void respectsEnableSnapshotAndDailyResetAndReplayClearsOldBook() {
        FalseBreakdownMonitor m = new FalseBreakdownMonitor();
        m.onTrade(110, 1, true, OPEN, true); m.onDepth(true, 105, 6000, OPEN);
        long now = OPEN + WallPatternMonitor.BID_MIN_DURATION_NS + 1;
        m.onTrade(104, 1, false, now, true); assertTrue(m.snapshot().entries.isEmpty());
        m.setEnabled(true); m.onTrade(110, 1, true, now, true);
        m.onTrade(104, 1, false, now, false); assertTrue(m.snapshot().entries.isEmpty());
        m.onTrade(110, 1, true, now, true); m.onTrade(104, 1, false, now + 1, true);
        assertEquals(1, m.snapshot().entries.size());
        m.onTimestamp(now + 86_400_000_000_000L);
        assertTrue(m.snapshot().entries.isEmpty()); assertEquals(WallPatternMonitor.Phase.BID_BREAKDOWN, m.snapshot().phase);
        m.onTrade(110, 1, true, now + 86_400_000_000_001L, true);
        m.onTimestamp(OPEN); assertNull(m.snapshot().highlightedTick); assertTrue(m.snapshot().entries.isEmpty());
    }
}
