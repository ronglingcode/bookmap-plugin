package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.signal.SignalComposerConfig;
import com.google.gson.JsonParser;

class EventTimeTradeAttributionTest {
    @Test void prunedEvidenceDuringDelayedDecisionCannotBecomeWithdrawal() {
        EventTimeTradeAttribution a = new EventTimeTradeAttribution(SignalComposerConfig.defaults()); a.markReady(1);
        a.onTrade(5100, 3000, false, CLEAR); a.onTime(10_000_000_001L);
        assertEquals(PatternEvent.Coverage.GAP, a.attribute(clear(true)).coverage);
        assertEquals(PatternEvent.Attribution.UNKNOWN, a.attribute(clear(true)).attribution);
    }
    private static final long CLEAR = 3_000_000_001L;
    private EventTimeWallTracker.Clear clear(boolean bid) {
        EventTimeWallTracker t = new EventTimeWallTracker(1, SignalComposerConfig.defaults());
        t.onDepth(bid, 5100, 3000, 1); t.onTime(500_000_001L); t.onDepth(bid, 5100, 0, CLEAR);
        return t.onTime(CLEAR + 500_000_000L).clears.get(0);
    }
    @Test void attributesOnlySamePriceCorrectSideAndWindowUsingLong() {
        EventTimeTradeAttribution a = new EventTimeTradeAttribution(SignalComposerConfig.defaults()); a.markReady(1);
        a.onTrade(5100, 10000, false, 500_000_001L); // too old
        a.onTrade(5101, 10000, false, 2_000_000_001L); // wrong price
        a.onTrade(5100, 10000, true, 2_100_000_001L); // wrong aggressor
        a.onTrade(5100, 2100, false, CLEAR); a.onTrade(5100, 100, false, CLEAR + 500_000_000L);
        EventTimeTradeAttribution.Result r = a.attribute(clear(true));
        assertEquals(2200, r.matchingVolume); assertEquals(PatternEvent.Attribution.PROBABLE_CONSUMPTION, r.attribution);
        assertEquals(3000, r.evidence(clear(true)).removedSize);
        a.reset(); a.markReady(1); a.onTrade(5100, 3_000_000_000L, true, CLEAR);
        assertEquals(3_000_000_000L, a.attribute(clear(false)).matchingVolume);
    }
    @Test void zeroKnownVolumeCanInferWithdrawalButUnknownSideAndWarmupCannot() {
        EventTimeTradeAttribution a = new EventTimeTradeAttribution(SignalComposerConfig.defaults());
        assertEquals(PatternEvent.Coverage.WARMUP, a.attribute(clear(true)).coverage);
        a.markReady(1); assertEquals(PatternEvent.Attribution.INFERRED_WITHDRAWAL, a.attribute(clear(true)).attribution);
        a.onTrade(5100, 100, null, CLEAR);
        assertEquals(PatternEvent.Coverage.GAP, a.attribute(clear(true)).coverage);
        assertEquals(PatternEvent.Attribution.UNKNOWN, a.attribute(clear(true)).attribution);
    }
    @Test void capacityDiscontinuityAndArithmeticOverflowAreNeverWithdrawal() {
        SignalComposerConfig c = SignalComposerConfig.parse(JsonParser.parseString("{\"maxAttributionTrades\":1}").getAsJsonObject());
        EventTimeTradeAttribution a = new EventTimeTradeAttribution(c); a.markReady(1);
        a.onTrade(5100, 3000, false, CLEAR); a.onTrade(5101, 100, false, CLEAR + 1);
        assertEquals(1, a.size()); assertEquals(PatternEvent.Coverage.GAP, a.attribute(clear(true)).coverage);
        a = new EventTimeTradeAttribution(SignalComposerConfig.defaults()); a.markReady(1);
        a.onTrade(5100, Long.MAX_VALUE, false, CLEAR); a.onTrade(5100, 1, false, CLEAR + 1);
        assertEquals(Long.MAX_VALUE, a.attribute(clear(true)).matchingVolume);
        assertEquals(PatternEvent.Attribution.UNKNOWN, a.attribute(clear(true)).attribution);
    }
}
