package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.SignalComposerActivationTest.*;
import com.bookmap.plugin.rong.patterns.FalseBreakdownMonitor;
import com.bookmap.plugin.rong.patterns.FalseBreakdownPainter;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FalseBreakdownCallbacksTest {
    @Test void replayDepthAloneConfirmsBreakdownEvenWhenComposerCoverageIsReset() throws Exception {
        withIsolatedActivation(() -> {
            RongPlugin plugin = SignalComposerCallbacksTest.callbackPlugin();
            FalseBreakdownMonitor monitor = new FalseBreakdownMonitor(); monitor.setEnabled(true);
            FalseBreakdownPainter painter = new FalseBreakdownPainter(monitor);
            field("falseBreakdownMonitor").set(plugin, monitor); field("falseBreakdownPainter").set(plugin, painter);
            try {
                long time = Instant.parse("2026-10-13T13:30:00Z").getEpochSecond() * 1_000_000_000L;
                plugin.onTimestamp(time); plugin.onSnapshotEnd();
                plugin.onDepth(true, 105, 6000); plugin.onDepth(true, 103, 1000); plugin.onDepth(false, 106, 1000);
                plugin.onTimestamp(time + 1_000_000_001L);
                assertEquals(105, monitor.snapshot().highlightedTick);
                field("compositionSnapshotComplete").setBoolean(plugin, false);
                plugin.onDepth(true, 105, 0); // Still only cleared: ask above the level.
                assertEquals(FalseBreakdownMonitor.Phase.CLEARED, monitor.snapshot().entries.get(0).phase);
                plugin.onDepth(false, 106, 0); plugin.onDepth(false, 104, 1000);
                assertEquals(FalseBreakdownMonitor.Phase.BREAKDOWN, monitor.snapshot().entries.get(0).phase);
            } finally { painter.shutdown(); }
        });
    }

    @Test void depthClearThenLevelOneAskBelowAndBidAboveDriveSeparatePhases() throws Exception {
        withIsolatedActivation(() -> {
            RongPlugin plugin = SignalComposerCallbacksTest.callbackPlugin();
            FalseBreakdownMonitor monitor = new FalseBreakdownMonitor(); monitor.setEnabled(true);
            FalseBreakdownPainter painter = new FalseBreakdownPainter(monitor);
            field("falseBreakdownMonitor").set(plugin, monitor);
            field("falseBreakdownPainter").set(plugin, painter);
            try {
                long time = Instant.parse("2026-10-13T13:30:00Z").getEpochSecond() * 1_000_000_000L;
                plugin.onTimestamp(time); plugin.onSnapshotEnd(); plugin.onBbo(110, 1, 111, 1);
                plugin.onDepth(true, 105, 6000); plugin.onTimestamp(time + 1_000_000_001L);
                plugin.onDepth(true, 105, 0);
                assertEquals(FalseBreakdownMonitor.Phase.CLEARED, monitor.snapshot().entries.get(0).phase);
                plugin.onTrade(104, 1, null); plugin.onBbo(104, 1, 105, 1);
                assertEquals(FalseBreakdownMonitor.Phase.CLEARED, monitor.snapshot().entries.get(0).phase);
                plugin.onTimestamp(time + 1_000_000_002L); plugin.onBbo(103, 1, 104, 1);
                assertEquals(FalseBreakdownMonitor.Phase.BREAKDOWN, monitor.snapshot().entries.get(0).phase);
                plugin.onTimestamp(time + 1_000_000_003L); plugin.onBbo(104, 1, 106, 1);
                assertEquals(FalseBreakdownMonitor.Phase.BREAKDOWN, monitor.snapshot().entries.get(0).phase);
                plugin.onTimestamp(time + 1_000_000_004L); plugin.onBbo(106, 1, 107, 1);
                assertEquals(FalseBreakdownMonitor.Phase.RECLAIMED, monitor.snapshot().entries.get(0).phase);
            } finally { painter.shutdown(); }
        });
    }
}
