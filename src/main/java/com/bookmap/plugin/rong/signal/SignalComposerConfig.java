package com.bookmap.plugin.rong.signal;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Immutable rules snapshot. File settings never belong to the broker configuration. */
public final class SignalComposerConfig {
    public final boolean enabled, valid;
    public final String error;
    public final Set<String> symbols;
    public final long normalTriggerSize, minimumTriggerSize, normalConfirmationSize, observationFloorSize;
    public final long historyRetentionMs, beforeWindowMs, afterWindowMs;
    public final int maxPriceDistanceTicks, directionalPriceToleranceTicks, maxTriggerDriftTicks;
    public final int maxEvents, maxCandidates, maxWallPhases, maxAttributionTrades;
    public final StrengthMultiples strengthMultiples;
    public final TriggerRequirements triggerRequirements;
    public final DetectorSettings detectors;

    private SignalComposerConfig(Builder b) {
        this(b, true, "");
    }
    private SignalComposerConfig(Builder b, boolean valid, String error) {
        this.valid = valid; this.error = error; enabled = valid && b.enabled;
        symbols = Collections.unmodifiableSet(new LinkedHashSet<>(b.symbols));
        normalTriggerSize = b.normalTriggerSize; minimumTriggerSize = b.minimumTriggerSize;
        normalConfirmationSize = b.normalConfirmationSize; observationFloorSize = b.observationFloorSize;
        historyRetentionMs = b.historyRetentionMs; beforeWindowMs = b.beforeWindowMs; afterWindowMs = b.afterWindowMs;
        maxPriceDistanceTicks = b.maxPriceDistanceTicks; directionalPriceToleranceTicks = b.directionalPriceToleranceTicks;
        maxTriggerDriftTicks = b.maxTriggerDriftTicks; maxEvents = b.maxEvents; maxCandidates = b.maxCandidates;
        maxWallPhases = b.maxWallPhases; maxAttributionTrades = b.maxAttributionTrades;
        strengthMultiples = b.strengthMultiples; triggerRequirements = b.triggerRequirements;
        detectors = new DetectorSettings(b.detectors);
    }

    public static SignalComposerConfig defaults() { return new SignalComposerConfig(new Builder()); }

    static final class Builder {
        boolean enabled;
        Set<String> symbols = new LinkedHashSet<>();
        long normalTriggerSize = 5000, minimumTriggerSize = 3000, normalConfirmationSize = 5000, observationFloorSize = 3000;
        long historyRetentionMs = 120000, beforeWindowMs = 30000, afterWindowMs = 30000;
        int maxPriceDistanceTicks = 20, directionalPriceToleranceTicks = 2, maxTriggerDriftTicks = 20;
        int maxEvents = 2048, maxCandidates = 64, maxWallPhases = 4096, maxAttributionTrades = 8192;
        StrengthMultiples strengthMultiples = new StrengthMultiples(1, 2, 5, 10);
        TriggerRequirements triggerRequirements = new TriggerRequirements(5000, 5000, 5000, 5000, 4000, 3000);
        DetectorBuilder detectors = new DetectorBuilder();
    }

    public static final class StrengthMultiples {
        public final double normal, strong, veryStrong, exceptional;
        StrengthMultiples(double normal, double strong, double veryStrong, double exceptional) {
            this.normal = normal; this.strong = strong; this.veryStrong = veryStrong; this.exceptional = exceptional;
        }
    }
    public static final class TriggerRequirements {
        public final long none, belowNormal, normal, strong, veryStrong, exceptional;
        TriggerRequirements(long none, long belowNormal, long normal, long strong, long veryStrong, long exceptional) {
            this.none = none; this.belowNormal = belowNormal; this.normal = normal;
            this.strong = strong; this.veryStrong = veryStrong; this.exceptional = exceptional;
        }
    }
    static final class DetectorBuilder {
        long wallLifetimeMs = 500, clearDecisionMs = 500, attributionLookbackMs = 2000, movePairWindowMs = 500;
        long rejectionHoldMs = 200, interactionWindowMs = 5000, breakoutWindowMs = 3000;
        double clearRemainingRatio = .1, consumptionRatio = .7, withdrawalMaxTradeRatio = .1;
        double moveSizeToleranceRatio = .1, growthRatio = .25;
        int approachDistanceTicks = 2, rejectionDistanceTicks = 2, breakoutDistanceTicks = 1;
    }
    public static final class DetectorSettings {
        public final long wallLifetimeMs, clearDecisionMs, attributionLookbackMs, movePairWindowMs;
        public final long rejectionHoldMs, interactionWindowMs, breakoutWindowMs;
        public final double clearRemainingRatio, consumptionRatio, withdrawalMaxTradeRatio, moveSizeToleranceRatio, growthRatio;
        public final int approachDistanceTicks, rejectionDistanceTicks, breakoutDistanceTicks;
        private DetectorSettings(DetectorBuilder b) {
            wallLifetimeMs = b.wallLifetimeMs; clearDecisionMs = b.clearDecisionMs;
            attributionLookbackMs = b.attributionLookbackMs; movePairWindowMs = b.movePairWindowMs;
            rejectionHoldMs = b.rejectionHoldMs; interactionWindowMs = b.interactionWindowMs; breakoutWindowMs = b.breakoutWindowMs;
            clearRemainingRatio = b.clearRemainingRatio; consumptionRatio = b.consumptionRatio;
            withdrawalMaxTradeRatio = b.withdrawalMaxTradeRatio; moveSizeToleranceRatio = b.moveSizeToleranceRatio;
            growthRatio = b.growthRatio; approachDistanceTicks = b.approachDistanceTicks;
            rejectionDistanceTicks = b.rejectionDistanceTicks; breakoutDistanceTicks = b.breakoutDistanceTicks;
        }
    }
}
