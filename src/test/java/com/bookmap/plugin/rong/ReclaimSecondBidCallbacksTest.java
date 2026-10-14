package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.SignalComposerActivationTest.*;
import com.bookmap.plugin.rong.patterns.ReclaimSecondBidMonitor;
import com.bookmap.plugin.rong.patterns.ReclaimSecondBidPainter;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReclaimSecondBidCallbacksTest {
    @Test void replaySellingAtBidTracksPartialFillsAndQuotesAboveBothKeepTracking() throws Exception {
        withIsolatedActivation(() -> {
            RongPlugin plugin = SignalComposerCallbacksTest.callbackPlugin();
            ReclaimSecondBidMonitor monitor = new ReclaimSecondBidMonitor(); monitor.setEnabled(true);
            ReclaimSecondBidPainter painter = new ReclaimSecondBidPainter(monitor);
            field("reclaimSecondBidMonitor").set(plugin, monitor); field("reclaimSecondBidPainter").set(plugin, painter);
            try {
                long time = Instant.parse("2026-10-13T13:30:00Z").getEpochSecond() * 1_000_000_000L;
                plugin.onTimestamp(time); plugin.onSnapshotEnd();
                plugin.onDepth(true, 107, 6000); plugin.onDepth(true, 105, 6000); plugin.onDepth(false, 108, 1000);
                plugin.onTimestamp(time + 1_000_000_001L); plugin.onTrade(107, 10, null);
                assertEquals(1, monitor.snapshot().fills.size()); assertEquals(105, monitor.snapshot().nextBidTick);
                plugin.onDepth(true, 107, 0); plugin.onTrade(105, 20, null);
                assertEquals(2, monitor.snapshot().fills.size());
                plugin.onBbo(106, 1, 109, 1); plugin.onBbo(108, 1, 109, 1);
                assertEquals(107, monitor.snapshot().fills.get(0).bidTick);
                assertEquals(105, monitor.snapshot().fills.get(1).bidTick);
            } finally { painter.shutdown(); }
        });
    }
}
