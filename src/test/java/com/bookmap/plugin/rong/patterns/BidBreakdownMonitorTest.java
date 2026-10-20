package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BidBreakdownMonitorTest {
    static final long BASE = Instant.parse("2026-10-13T13:30:00Z").getEpochSecond() * 1_000_000_000L;
    static final long MATURE = BASE + BidBreakdownMonitor.BID_MIN_DURATION_NS + 1;
    static BidBreakdownMonitor fresh(boolean falseBreakout) {
        BidBreakdownMonitor monitor = falseBreakout ? new FalseBreakoutMonitor() : new BidBreakdownMonitor(() -> true);
        monitor.setEnabled(true); monitor.onBbo(110, 111, BASE, true);
        if (falseBreakout) {
            monitor.onTrade(110, 1, true, BASE, true);
            monitor.onDepth(false, 112, 6000, BASE, true);
            monitor.onTrade(112, 6000, true, BASE, true);
        }
        return monitor;
    }
    static BidBreakdownMonitor monitor() { return monitor(false); }
    static BidBreakdownMonitor monitor(boolean falseBreakout) {
        BidBreakdownMonitor monitor = fresh(falseBreakout);
        monitor.onDepth(true, 110, 4500, BASE, true);
        monitor.onDepth(true, 108, 5000, BASE, true);
        monitor.onTimestamp(MATURE, true); return monitor;
    }
    static void breakBid(BidBreakdownMonitor monitor, int tick, long time) {
        monitor.onDepth(true, tick, 0, time, true);
        monitor.onBbo(tick - 2, tick - 1, time, true);
    }
    @Test void standaloneLiquidityGateReadinessAndNoRetroactiveAskTimer() {
        AtomicBoolean gate = new AtomicBoolean();
        BidBreakdownMonitor monitor = new BidBreakdownMonitor(gate::get); monitor.setEnabled(true);
        monitor.onBbo(110, 111, BASE, true); monitor.onDepth(true, 110, 4500, BASE, true);
        monitor.onTimestamp(MATURE, true); assertNull(monitor.snapshot().nextBidTick);
        monitor.onBbo(108, 109, MATURE, true); gate.set(true);
        monitor.onTimestamp(MATURE + 2_000_000_000L, false); assertNull(monitor.snapshot().nextBidTick);
        monitor.onTimestamp(MATURE + 3_000_000_000L, true); assertTrue(monitor.snapshot().entries.isEmpty());
        monitor.onBbo(110, 111, MATURE + 3_000_000_001L, true); assertEquals(110, monitor.snapshot().nextBidTick);
        gate.set(false); monitor.onTimestamp(MATURE + 3_000_000_002L, true); assertNull(monitor.snapshot().nextBidTick);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void inclusive4500And5000RequireMoreThanOneSecond(boolean falseBreakout) {
        for (int size : new int[]{4500, 5000}) {
            BidBreakdownMonitor monitor = fresh(falseBreakout);
            monitor.onDepth(true, 110, size, BASE, true);
            monitor.onTimestamp(BASE + 1_000_000_000L, true); assertNull(monitor.snapshot().nextBidTick);
            monitor.onTimestamp(MATURE, true); assertEquals(110, monitor.snapshot().nextBidTick);
        }
        BidBreakdownMonitor monitor = fresh(falseBreakout);
        monitor.onDepth(true, 110, 4499, BASE, true);
        monitor.onTimestamp(MATURE, true); assertNull(monitor.snapshot().nextBidTick);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sizeDipRestartsPersistenceBeforeQualification(boolean falseBreakout) {
        BidBreakdownMonitor monitor = fresh(falseBreakout);
        monitor.onDepth(true, 110, 5000, BASE, true);
        monitor.onDepth(true, 110, 4499, BASE + 500_000_000L, true);
        monitor.onDepth(true, 110, 4500, BASE + 600_000_000L, true);
        monitor.onTimestamp(BASE + 1_600_000_000L, true); assertNull(monitor.snapshot().nextBidTick);
        monitor.onTimestamp(BASE + 1_600_000_001L, true); assertEquals(110, monitor.snapshot().nextBidTick);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void quoteOnlyEntryAtOneSecondThenReclaimAtEqualityAndContinue(boolean falseBreakout) {
        BidBreakdownMonitor monitor = monitor(falseBreakout); breakBid(monitor, 110, MATURE);
        monitor.onTimestamp(MATURE + 999_999_999L, true); assertTrue(monitor.snapshot().entries.isEmpty());
        monitor.onTimestamp(MATURE + 1_000_000_000L, true);
        assertEquals(1, monitor.snapshot().entries.size()); assertEquals(108, monitor.snapshot().nextBidTick);
        assertEquals(MATURE + 1_000_000_000L, monitor.snapshot().entries.get(0).entryTimeNs);
        monitor.onBbo(109, 111, MATURE + 2_000_000_000L, true); assertEquals(0, monitor.snapshot().entries.get(0).reclaimTimeNs);
        monitor.onBbo(110, 111, MATURE + 2_000_000_001L, true);
        assertEquals(MATURE + 2_000_000_001L, monitor.snapshot().entries.get(0).reclaimTimeNs);
        monitor.onDepth(true, 110, 9000, MATURE + 2_000_000_002L, true); assertEquals(108, monitor.snapshot().nextBidTick);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void askAtLevelOrInvalidQuoteRestartsContinuousTimer(boolean falseBreakout) {
        for (boolean invalid : new boolean[]{false, true}) {
            BidBreakdownMonitor monitor = monitor(falseBreakout); breakBid(monitor, 110, MATURE);
            // Keep the wall present so a failed test can rearm at the same level.
            monitor.onDepth(true, 110, 4500, MATURE + 1, true);
            monitor.onBbo(108, invalid ? 108 : 110, MATURE + 500_000_000L, true);
            monitor.onBbo(110, 111, MATURE + 600_000_000L, true);
            monitor.onTimestamp(MATURE + 1_000_000_002L, true);
            monitor.onBbo(108, 109, MATURE + 1_500_000_000L, true);
            monitor.onTimestamp(MATURE + 2_499_999_999L, true); assertTrue(monitor.snapshot().entries.isEmpty());
            monitor.onTimestamp(MATURE + 2_500_000_000L, true); assertEquals(1, monitor.snapshot().entries.size());
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void clearingStartsWindowButEntryStillRequiresAskConfirmation(boolean falseBreakout) {
        BidBreakdownMonitor monitor = monitor(falseBreakout);
        monitor.onDepth(true, 110, 0, MATURE, true);
        monitor.onTrade(109, 1, MATURE + 1, true);
        monitor.onTimestamp(MATURE + 2_000_000_000L, true); assertTrue(monitor.snapshot().entries.isEmpty());
        monitor.onBbo(108, 109, MATURE + 2_000_000_001L, true);
        monitor.onTimestamp(MATURE + 4_000_000_000L, true); assertEquals(1, monitor.snapshot().entries.size());
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void quoteCrossingAndPartialDepthDoNotStartWindowOrAllowEntry(boolean falseBreakout) {
        BidBreakdownMonitor monitor = monitor(falseBreakout);
        monitor.onBbo(108, 109, MATURE, true); // Both quotes below the old bid, but its depth is not cleared.
        monitor.onDepth(true, 110, 1000, MATURE + 1, true);
        monitor.onTimestamp(MATURE + 20_000_000_000L, true);
        assertEquals(110, monitor.snapshot().nextBidTick);
        assertTrue(monitor.snapshot().entries.isEmpty());
        monitor.onDepth(true, 110, 0, MATURE + 20_000_000_001L, true);
        monitor.onTimestamp(MATURE + 21_000_000_000L, true); assertTrue(monitor.snapshot().entries.isEmpty());
        monitor.onTimestamp(MATURE + 21_000_000_001L, true);
        assertEquals(110, monitor.snapshot().entries.get(0).bidTick);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void fifteenSecondWindowStartsAtBreakdownRatherThanInitialSelection(boolean falseBreakout) {
        BidBreakdownMonitor monitor = monitor(falseBreakout);
        monitor.onTimestamp(MATURE + 30_000_000_000L, true);
        assertEquals(110, monitor.snapshot().nextBidTick);
        monitor.onDepth(true, 110, 0, MATURE + 30_000_000_000L, true);
        monitor.onBbo(108, 109, MATURE + 43_000_000_000L, true);
        monitor.onTimestamp(MATURE + 44_000_000_000L, true);
        assertEquals(110, monitor.snapshot().entries.get(0).bidTick);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void brokenBidSurvivesStraddlingQuotesAndRemovalUntilAskConfirms(boolean falseBreakout) {
        BidBreakdownMonitor monitor = monitor(falseBreakout);
        monitor.onBbo(109, 111, MATURE, true); // Bid crosses first; ask is still above the old 110 bid.
        monitor.onDepth(true, 110, 0, MATURE + 1, true);
        monitor.onDepth(true, 109, 6000, MATURE + 2, true);
        monitor.onTimestamp(MATURE + 10_000_000_000L, true);
        assertEquals(110, monitor.snapshot().nextBidTick); // Keep this level instead of switching to 109.
        monitor.onBbo(108, 109, MATURE + 13_000_000_000L, true);
        monitor.onTimestamp(MATURE + 14_000_000_000L, true);
        assertEquals(110, monitor.snapshot().entries.get(0).bidTick);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void askMustFinishConfirmationByTheOriginalFifteenSecondDeadline(boolean falseBreakout) {
        for (long confirmationEnd : new long[]{15_000_000_000L, 15_000_000_001L}) {
            BidBreakdownMonitor monitor = monitor(falseBreakout);
            monitor.onBbo(109, 111, MATURE, true);
            monitor.onDepth(true, 110, 0, MATURE, true);
            monitor.onBbo(108, 109, MATURE + confirmationEnd - BidBreakdownMonitor.CONFIRMATION_NS, true);
            monitor.onTimestamp(MATURE + confirmationEnd, true);
            assertEquals(confirmationEnd == 15_000_000_000L ? 1 : 0, monitor.snapshot().entries.size());
            if (confirmationEnd > 15_000_000_000L) assertFalse(Integer.valueOf(110).equals(monitor.snapshot().nextBidTick));
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void recoveryAndRepeatedBreakDoNotExtendTheFifteenSecondDeadline(boolean falseBreakout) {
        BidBreakdownMonitor monitor = monitor(falseBreakout);
        monitor.onDepth(true, 110, 0, MATURE, true);
        monitor.onBbo(110, 111, MATURE + 8_000_000_000L, true);
        monitor.onDepth(true, 110, 4500, MATURE + 8_000_000_001L, true);
        monitor.onDepth(true, 110, 0, MATURE + 8_000_000_002L, true);
        monitor.onBbo(109, 110, MATURE + 14_000_000_000L, true);
        monitor.onTimestamp(MATURE + 15_000_000_001L, true);
        assertTrue(monitor.snapshot().entries.isEmpty());
        assertFalse(Integer.valueOf(110).equals(monitor.snapshot().nextBidTick));
        monitor.onBbo(108, 109, MATURE + 16_000_000_000L, true);
        monitor.onTimestamp(MATURE + 17_000_000_000L, true);
        assertTrue(monitor.snapshot().entries.stream().noneMatch(entry -> entry.bidTick == 110));
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void fiveDistinctEntriesStopScanningButAllReclaimsContinue(boolean falseBreakout) {
        BidBreakdownMonitor monitor = fresh(falseBreakout);
        for (int tick = 110; tick >= 100; tick -= 2) monitor.onDepth(true, tick, 4500, BASE, true);
        monitor.onTimestamp(MATURE, true);
        for (int i = 0; i < 5; i++) {
            long time = MATURE + i * 2_000_000_000L;
            breakBid(monitor, 110 - 2 * i, time);
            monitor.onTimestamp(time + BidBreakdownMonitor.CONFIRMATION_NS, true);
        }
        assertEquals(5, monitor.snapshot().entries.size()); assertNull(monitor.snapshot().nextBidTick);
        monitor.onBbo(111, 112, MATURE + 12_000_000_000L, true);
        assertTrue(monitor.snapshot().entries.stream().allMatch(entry -> entry.reclaimTimeNs > 0));
        monitor.onDepth(true, 110, 4500, MATURE + 12_000_000_001L, true);
        monitor.onTimestamp(MATURE + 14_000_000_000L, true); assertNull(monitor.snapshot().nextBidTick);
        monitor.setEnabled(false); monitor.setEnabled(true);
        monitor.onTimestamp(MATURE + 15_000_000_000L, true); assertEquals(5, monitor.snapshot().entries.size());
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void disableAndReadinessLossDiscardPendingConfirmation(boolean falseBreakout) {
        BidBreakdownMonitor monitor = monitor(falseBreakout); breakBid(monitor, 110, MATURE);
        monitor.setEnabled(false); monitor.onTimestamp(MATURE + 2_000_000_000L, true);
        monitor.setEnabled(true); monitor.onTimestamp(MATURE + 3_000_000_000L, true);
        assertTrue(monitor.snapshot().entries.isEmpty());
        monitor.onDepth(true, 110, 4500, MATURE + 3_000_000_001L, true);
        monitor.onBbo(110, 111, MATURE + 5_000_000_000L, true);
        breakBid(monitor, 110, MATURE + 5_000_000_001L);
        monitor.onTimestamp(MATURE + 5_500_000_000L, false);
        monitor.onTimestamp(MATURE + 7_000_000_000L, true); assertTrue(monitor.snapshot().entries.isEmpty());
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void replaySeekClearsDepthEntriesAndStartState(boolean falseBreakout) {
        BidBreakdownMonitor monitor = monitor(falseBreakout); breakBid(monitor, 110, MATURE);
        monitor.onTimestamp(MATURE + 1_000_000_000L, true); assertEquals(1, monitor.snapshot().entries.size());
        monitor.onTimestamp(BASE, false); assertTrue(monitor.snapshot().entries.isEmpty()); assertNull(monitor.snapshot().nextBidTick);
        monitor.onBbo(110, 111, BASE + 1, true); assertNull(monitor.snapshot().nextBidTick);
        assertEquals(falseBreakout, monitor.snapshot().waitingForOffer);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void regularSessionEndBlocksSignalsAndNewDayResets(boolean falseBreakout) {
        BidBreakdownMonitor monitor = monitor(falseBreakout); breakBid(monitor, 110, MATURE);
        long close = Instant.parse("2026-10-13T20:00:00Z").getEpochSecond() * 1_000_000_000L;
        monitor.onTimestamp(close, true); assertTrue(monitor.snapshot().entries.isEmpty()); assertNull(monitor.snapshot().nextBidTick);
        monitor.onTimestamp(BASE + 86_400_000_000_000L, true); assertTrue(monitor.snapshot().entries.isEmpty());
        assertEquals(falseBreakout, monitor.snapshot().waitingForOffer);
    }
}
