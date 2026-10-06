package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.signal.ResetReason;

class ObservationClockTest {
    static long ns(String iso) { return Instant.parse(iso).getEpochSecond() * 1_000_000_000L; }
    private ObservationClock.Tick advance(ObservationClock c, String iso) {
        return c.advance(ns(iso), PatternEvent.TimestampProvenance.MARKET);
    }
    @Test void snapshotAndRegularSessionBoundariesGateObservations() {
        ObservationClock c = new ObservationClock();
        assertFalse(advance(c, "2026-10-06T13:29:59Z").usable); c.markReady();
        assertTrue(advance(c, "2026-10-06T13:30:00Z").usable);
        assertTrue(advance(c, "2026-10-06T19:59:59Z").usable);
        ObservationClock.Tick close = advance(c, "2026-10-06T20:00:00Z");
        assertFalse(close.usable); assertEquals(ResetReason.NEW_SESSION, close.resetReason);
    }
    @Test void backwardsCallbackRequiresFreshReadinessAndNewEpoch() {
        ObservationClock c = new ObservationClock(); c.markReady();
        advance(c, "2026-10-06T14:00:00Z"); long epoch = c.epoch();
        ObservationClock.Tick seek = advance(c, "2026-10-06T13:45:00Z");
        assertEquals(ResetReason.REPLAY_SEEK, seek.resetReason); assertFalse(seek.usable); assertEquals(epoch + 1, seek.epoch);
        assertFalse(advance(c, "2026-10-06T13:46:00Z").usable);
        c.markReady(); assertTrue(advance(c, "2026-10-06T13:47:00Z").usable);
    }
    @Test void fallbackNeverAdvancesClockAndNextDateClearsSession() {
        ObservationClock c = new ObservationClock(); c.markReady(); advance(c, "2026-10-06T14:00:00Z");
        long time = c.watermarkNs();
        assertFalse(c.advance(time + 100, PatternEvent.TimestampProvenance.FALLBACK).usable);
        assertEquals(time, c.watermarkNs());
        assertEquals(ResetReason.NEW_SESSION, advance(c, "2026-10-07T14:00:00Z").resetReason);
        assertTrue(c.usable()); assertFalse(c.reset(ResetReason.DISABLED).usable);
    }
}
