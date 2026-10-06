package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.signal.SignalComposerConfig;
import com.google.gson.JsonParser;

class EventTimeRelocationTrackerTest {
    private EventTimeWallTracker.Clear clear() {
        EventTimeWallTracker t = new EventTimeWallTracker(1, SignalComposerConfig.defaults());
        t.onDepth(true, 5100, 3000, 1); t.onTime(500_000_001L);
        t.onDepth(true, 5100, 0, 3_000_000_001L); return t.onTime(3_500_000_001L).clears.get(0);
    }
    @Test void pairsSameSideSimilarSizeAndConsumesReplacementOnce() {
        EventTimeRelocationTracker r = new EventTimeRelocationTracker(SignalComposerConfig.defaults());
        r.onDepth(true, 5101, 0, 3300, 3_500_000_001L);
        EventTimeRelocationTracker.Status status = r.match(clear());
        assertEquals(EventTimeRelocationTracker.Status.PROBABLE_MOVE, status);
        EventTimeTradeAttribution a = new EventTimeTradeAttribution(SignalComposerConfig.defaults()); a.markReady(1);
        assertEquals(PatternEvent.Attribution.PROBABLE_MOVE, a.attribute(clear()).evidence(clear(), status).attribution);
        assertEquals(EventTimeRelocationTracker.Status.NO_MATCH, r.match(clear()));
    }
    @Test void rejectsWrongSidePriceSizeAndTime() {
        EventTimeRelocationTracker r = new EventTimeRelocationTracker(SignalComposerConfig.defaults());
        r.onDepth(true, 5101, 0, 3000, 2_500_000_000L);
        r.onDepth(false, 5101, 0, 3000, 3_000_000_001L);
        r.onDepth(true, 5100, 0, 3000, 3_000_000_001L);
        r.onDepth(true, 5102, 0, 3301, 3_000_000_001L);
        assertEquals(EventTimeRelocationTracker.Status.NO_MATCH, r.match(clear()));
    }
    @Test void lostIncreaseCoverageIsExplicitAndResetClearsIt() {
        SignalComposerConfig c = SignalComposerConfig.parse(JsonParser.parseString("{\"maxWallPhases\":1}").getAsJsonObject());
        EventTimeRelocationTracker r = new EventTimeRelocationTracker(c);
        r.onDepth(true, 5101, 0, 3000, 3_000_000_001L);
        r.onDepth(false, 5102, 0, 4000, 3_000_000_002L);
        assertEquals(1, r.size()); assertEquals(EventTimeRelocationTracker.Status.COVERAGE_GAP, r.match(clear()));
        r.reset(); assertEquals(EventTimeRelocationTracker.Status.NO_MATCH, r.match(clear()));
    }
}
