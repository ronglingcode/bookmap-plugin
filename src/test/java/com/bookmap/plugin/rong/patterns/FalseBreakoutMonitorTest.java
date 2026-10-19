package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FalseBreakoutMonitorTest {
    static final long OPEN = BidBreakdownMonitorTest.BASE;
    static FalseBreakoutMonitor started() {
        FalseBreakoutMonitor monitor = new FalseBreakoutMonitor(); monitor.setEnabled(true);
        monitor.onTrade(100, 1, true, OPEN, true); return monitor;
    }
    static void breakout(FalseBreakoutMonitor monitor) {
        monitor.onDepth(false, 110, 6000, OPEN, true);
        monitor.onTrade(110, 6000, true, OPEN, true);
        assertFalse(monitor.snapshot().waitingForOffer);
    }
    @Test void offerThresholdRemainsPremarketBasedAndDoesNotChangeSharedBidCutoff() {
        for (int threshold : new int[]{2000, 20000}) {
            AtomicInteger offerThreshold = new AtomicInteger(threshold);
            FalseBreakoutMonitor monitor = new FalseBreakoutMonitor(offerThreshold::get); monitor.setEnabled(true);
            monitor.onTrade(100, 1, true, OPEN, true);
            monitor.onDepth(false, 130, threshold, OPEN, true); assertNull(monitor.snapshot().offerTick);
            monitor.onDepth(false, 130, threshold + 1, OPEN, true); assertEquals(130, monitor.snapshot().offerTick);
            monitor.onDepth(true, 105, 4500, OPEN, true);
            monitor.onTimestamp(BidBreakdownMonitorTest.MATURE, true); assertNull(monitor.snapshot().nextBidTick);
            monitor.onTrade(130, threshold + 1, true, BidBreakdownMonitorTest.MATURE, true);
            assertEquals(105, monitor.snapshot().nextBidTick);
            offerThreshold.set(50000);
            monitor.onBbo(103, 104, BidBreakdownMonitorTest.MATURE + 1, true);
            monitor.onTimestamp(BidBreakdownMonitorTest.MATURE + 1_000_000_001L, true);
            assertEquals(1, monitor.snapshot().entries.size());
        }
    }
    @Test void nearestOfferStrictlyAboveTradeAndCancellationDoesNotArmBids() {
        FalseBreakoutMonitor monitor = started();
        monitor.onDepth(false, 100, 7000, OPEN, true);
        monitor.onDepth(false, 110, 6000, OPEN, true); assertEquals(110, monitor.snapshot().offerTick);
        monitor.onDepth(false, 107, 7000, OPEN, true); assertEquals(107, monitor.snapshot().offerTick);
        monitor.onDepth(false, 107, 0, OPEN, true);
        monitor.onTrade(100, 1, true, OPEN, true); assertEquals(110, monitor.snapshot().offerTick);
        assertTrue(monitor.snapshot().waitingForOffer);
    }
    @Test void depthBeforeExecutionCanCompleteOfferButPrintAboveWithoutBuysCannot() {
        FalseBreakoutMonitor monitor = started(); monitor.onDepth(false, 110, 6000, OPEN, true);
        monitor.onDepth(false, 110, 0, OPEN, true); assertTrue(monitor.snapshot().waitingForOffer);
        monitor.onTrade(110, 6000, true, OPEN, true); assertFalse(monitor.snapshot().waitingForOffer);
        monitor = started(); monitor.onDepth(false, 110, 6000, OPEN, true);
        monitor.onTrade(111, 6000, true, OPEN, true); assertTrue(monitor.snapshot().waitingForOffer);
    }
    @Test void buyingAccumulatesAndExecutionAboveAfterPartialFillAlsoCompletesBreakout() {
        for (boolean above : new boolean[]{false, true}) {
            FalseBreakoutMonitor monitor = started(); monitor.onDepth(false, 110, 6000, OPEN, true);
            monitor.onTrade(110, 6000, false, OPEN, true); assertTrue(monitor.snapshot().waitingForOffer);
            monitor.onTrade(110, 2000, true, OPEN, true);
            monitor.onDepth(false, 110, 4000, OPEN, true);
            monitor.onTrade(above ? 111 : 110, above ? 1 : 4000, true, OPEN, true);
            assertFalse(monitor.snapshot().waitingForOffer);
        }
    }
    @Test void disabledOrPreSnapshotBuyingCannotCompleteOfferBreakout() {
        FalseBreakoutMonitor monitor = started(); monitor.onDepth(false, 110, 6000, OPEN, false);
        monitor.onTrade(110, 6000, true, OPEN, false); assertTrue(monitor.snapshot().waitingForOffer);
        monitor.setEnabled(false); monitor.onTrade(110, 6000, true, OPEN, true); assertTrue(monitor.snapshot().waitingForOffer);
        monitor.setEnabled(true); monitor.onTrade(110, 6000, true, OPEN, true); assertFalse(monitor.snapshot().waitingForOffer);
    }
}
