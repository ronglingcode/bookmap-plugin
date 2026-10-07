package com.bookmap.plugin.rong.patterns;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import com.bookmap.plugin.rong.signal.ResetReason;

/** Callback time only. Detector occurrence times never enter this lifecycle clock. */
public final class ObservationClock {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private long epoch = 1, watermarkNs;
    private boolean ready, regularSession;
    private LocalDate date;

    public static final class Tick {
        public final long epoch, timeNs;
        public final boolean usable;
        public final ResetReason resetReason;
        private Tick(long epoch, long timeNs, boolean usable, ResetReason reason) {
            this.epoch = epoch; this.timeNs = timeNs; this.usable = usable; resetReason = reason;
        }
    }
    public Tick advance(long callbackNs, PatternEvent.TimestampProvenance provenance) {
        if (callbackNs <= 0 || provenance != PatternEvent.TimestampProvenance.MARKET)
            return new Tick(epoch, watermarkNs, false, null);
        ResetReason reason = null;
        if (watermarkNs > 0 && callbackNs < watermarkNs) {
            reason = ResetReason.REPLAY_SEEK; epoch++; ready = false; date = null; regularSession = false;
        }
        ZonedDateTime ny = Instant.ofEpochSecond(callbackNs / 1_000_000_000L, callbackNs % 1_000_000_000L).atZone(NEW_YORK);
        boolean regular = !ny.toLocalTime().isBefore(LocalTime.of(9, 30)) && ny.toLocalTime().isBefore(LocalTime.of(16, 0));
        if (reason == null && date != null && (!date.equals(ny.toLocalDate()) || regular != regularSession)) {
            epoch++; reason = ResetReason.NEW_SESSION;
        }
        date = ny.toLocalDate(); regularSession = regular; watermarkNs = callbackNs;
        return new Tick(epoch, watermarkNs, ready && regular, reason);
    }
    public void markReady() { ready = true; }
    public long epoch() { return epoch; }
    public long watermarkNs() { return watermarkNs; }
    public boolean usable() { return ready && regularSession && watermarkNs > 0; }
    public String status() {
        if (!ready) return "Waiting for snapshot / realtime readiness";
        if (watermarkNs <= 0) return "Waiting for market timestamp";
        if (!regularSession) return "Outside regular hours (09:30–16:00 New York)";
        return "Scanning";
    }
    public Tick reset(ResetReason reason) {
        epoch++; watermarkNs = 0; ready = false; regularSession = false; date = null;
        return new Tick(epoch, 0, false, reason);
    }
}
