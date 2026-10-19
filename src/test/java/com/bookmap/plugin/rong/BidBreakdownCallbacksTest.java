package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.SignalComposerActivationTest.*;
import com.bookmap.plugin.rong.patterns.*;
import java.lang.reflect.Method;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BidBreakdownCallbacksTest {
    @Test void falseBreakoutGetsLevelOneQuotesAndUsesSharedEntryReclaimRules() throws Exception {
        withIsolatedActivation(() -> {
            RongPlugin plugin = SignalComposerCallbacksTest.callbackPlugin();
            FalseBreakoutMonitor monitor = new FalseBreakoutMonitor(); monitor.setEnabled(true);
            FalseBreakoutPainter painter = new FalseBreakoutPainter(monitor);
            field("falseBreakoutMonitor").set(plugin, monitor); field("falseBreakoutPainter").set(plugin, painter);
            long time = Instant.parse("2026-10-13T13:30:00Z").getEpochSecond() * 1_000_000_000L;
            try {
                plugin.onTimestamp(time); plugin.onSnapshotEnd();
                plugin.onTrade(100, 1, null); plugin.onDepth(false, 110, 6000);
                plugin.onDepth(true, 105, 4500); plugin.onTrade(110, 6000, null);
                assertFalse(monitor.snapshot().waitingForOffer);
                long mature = time + 1_000_000_001L;
                plugin.onTimestamp(mature); plugin.onBbo(105, 1, 106, 1);
                assertEquals(105, monitor.snapshot().nextBidTick);
                plugin.onDepth(true, 105, 0); plugin.onBbo(103, 1, 104, 1);
                plugin.onTimestamp(mature + 999_999_999L); assertTrue(monitor.snapshot().entries.isEmpty());
                plugin.onTimestamp(mature + 1_000_000_000L); assertEquals(1, monitor.snapshot().entries.size());
                plugin.onBbo(105, 1, 106, 1); assertEquals(mature + 1_000_000_000L, monitor.snapshot().entries.get(0).reclaimTimeNs);
            } finally { painter.shutdown(); }
        });
    }
    @Test void sharedEntryGateUnlocksOnRangeExpansionThenCallbacksConfirmAndReclaim() throws Exception {
        withIsolatedActivation(() -> {
            RongPlugin plugin = SignalComposerCallbacksTest.callbackPlugin();
            SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
            Object previousServer = field("sharedServer").get(null);
            field("sharedServer").set(null, server);
            server.registerSymbol("TEST", (OrderBookState) field("orderBook").get(plugin), .01);
            Method gate = RongPlugin.class.getDeclaredMethod("isEntryLiquidityReady"); gate.setAccessible(true);
            BidBreakdownMonitor monitor = new BidBreakdownMonitor(() -> {
                try { return (boolean) gate.invoke(plugin); } catch (Exception e) { throw new AssertionError(e); }
            });
            monitor.setEnabled(true); BidBreakdownPainter painter = new BidBreakdownPainter(monitor);
            field("bidBreakdownMonitor").set(plugin, monitor); field("bidBreakdownPainter").set(plugin, painter);
            long time = Instant.parse("2026-10-13T13:30:00Z").getEpochSecond() * 1_000_000_000L;
            try {
                plugin.onTimestamp(time); plugin.onSnapshotEnd(); plugin.onBbo(110, 1, 111, 1);
                plugin.onDepth(true, 110, 4000); plugin.onDepth(false, 112, 5000);
                plugin.onTrade(110, 1, null); assertNull(monitor.snapshot().nextBidTick);
                // The required 5K order lies outside the traded range until this print.
                plugin.onTrade(112, 1, null); assertTrue(monitor.snapshot().liquid);
                assertNull(monitor.snapshot().nextBidTick); // A 4K bid is too small even with entries unlocked.
                plugin.onDepth(true, 110, 4500); assertNull(monitor.snapshot().nextBidTick);
                plugin.onTimestamp(time + 1_000_000_000L); assertNull(monitor.snapshot().nextBidTick);
                long mature = time + 1_000_000_001L;
                plugin.onTimestamp(mature); assertEquals(110, monitor.snapshot().nextBidTick);
                plugin.onDepth(false, 112, 0); assertTrue(monitor.snapshot().liquid);
                plugin.onDepth(true, 110, 0); plugin.onBbo(108, 1, 109, 1);
                plugin.onTimestamp(mature + 999_999_999L); assertTrue(monitor.snapshot().entries.isEmpty());
                plugin.onTimestamp(mature + 1_000_000_000L); assertEquals(1, monitor.snapshot().entries.size());
                plugin.onBbo(110, 1, 111, 1); assertEquals(mature + 1_000_000_000L, monitor.snapshot().entries.get(0).reclaimTimeNs);
                plugin.onTimestamp(time + 1); assertTrue(monitor.snapshot().entries.isEmpty());
                assertFalse(field("patternMonitorSnapshotComplete").getBoolean(plugin));
                plugin.onDepth(true, 110, 5000); plugin.onTrade(110, 1, null);
                plugin.onSnapshotEnd(); plugin.onBbo(110, 1, 111, 1); assertNull(monitor.snapshot().nextBidTick);
                plugin.onTimestamp(time + 1_000_000_002L); assertEquals(110, monitor.snapshot().nextBidTick);
            } finally { painter.shutdown(); server.shutdown(); field("sharedServer").set(null, previousServer); }
        });
    }
}
