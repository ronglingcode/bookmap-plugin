package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.signal.SignalComposerConfig;
import com.google.gson.JsonParser;

class EventTimeWallTrackerTest {
    @Test void independentThreeThousandFloorQualifiesAfterInclusiveLifetime() {
        EventTimeWallTracker t = new EventTimeWallTracker(1, SignalComposerConfig.defaults());
        t.onDepth(true, 5100, 3000, 1);
        assertTrue(t.onTime(500_000_000L).qualified.isEmpty());
        assertEquals(3000, t.onTime(500_000_001L).qualified.get(0).size);
        assertTrue(t.onTime(600_000_001L).qualified.isEmpty());
        String id = t.active(true, 5100).phaseId; t.onDepth(true, 5100, 4000, 700_000_001L);
        assertEquals(id, t.active(true, 5100).phaseId);
    }
    @Test void flashWallsAreRemovedAndSnapshotSeedingIsNotImmediateQualification() {
        EventTimeWallTracker t = new EventTimeWallTracker(1, SignalComposerConfig.defaults());
        t.seed(false, 5120, 60000, 1); assertFalse(t.active(false, 5120).qualified);
        t.onDepth(false, 5120, 0, 100_000_001L);
        assertTrue(t.onTime(600_000_001L).qualified.isEmpty()); assertNull(t.active(false, 5120));
        t.seed(false, 5120, 60000, 700_000_001L); assertEquals(1, t.onTime(1_200_000_001L).qualified.size());
    }
    @Test void capAndResetAreExplicitAndBounded() {
        SignalComposerConfig c = SignalComposerConfig.parse(JsonParser.parseString("{\"maxWallPhases\":1}").getAsJsonObject());
        EventTimeWallTracker t = new EventTimeWallTracker(1, c);
        t.onDepth(true, 5100, 3000, 1);
        assertEquals(1, t.onDepth(false, 5120, 3000, 2).diagnostics.size());
        assertEquals(1, t.snapshot().size()); assertNull(t.active(true, 5100));
        t.reset(2); assertTrue(t.snapshot().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> t.onTime(0));
    }
}
