package com.bookmap.plugin.rong.patterns;

final class WallSnapshot {
    final String phaseId;
    final boolean bid;
    final int priceTick;
    final int initialSize;
    final int peakSize;
    final int currentSize;
    final long firstSeenMs;
    final long qualifiedAtMs;
    final long clearedAtMs;
    final int tradedAtClear;
    final int effectiveThreshold;
    final int sessionHighAtClear;
    final int sessionLowAtClear;
    final boolean sweep;

    WallSnapshot(
            String phaseId,
            boolean bid,
            int priceTick,
            int initialSize,
            int peakSize,
            int currentSize,
            long firstSeenMs,
            long qualifiedAtMs,
            long clearedAtMs,
            int tradedAtClear,
            int effectiveThreshold,
            int sessionHighAtClear,
            int sessionLowAtClear,
            boolean sweep) {
        this.phaseId = phaseId;
        this.bid = bid;
        this.priceTick = priceTick;
        this.initialSize = initialSize;
        this.peakSize = peakSize;
        this.currentSize = currentSize;
        this.firstSeenMs = firstSeenMs;
        this.qualifiedAtMs = qualifiedAtMs;
        this.clearedAtMs = clearedAtMs;
        this.tradedAtClear = tradedAtClear;
        this.effectiveThreshold = effectiveThreshold;
        this.sessionHighAtClear = sessionHighAtClear;
        this.sessionLowAtClear = sessionLowAtClear;
        this.sweep = sweep;
    }

    long durationMs(long nowMs) {
        long end = clearedAtMs > 0 ? clearedAtMs : nowMs;
        return Math.max(0, end - firstSeenMs);
    }
}
