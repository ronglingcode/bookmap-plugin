package com.bookmap.plugin.rong.signal;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SignalComposerConfigTest {
    @Test void defaultsKeepTheDetectionFloorIndependentAndDisabled() {
        SignalComposerConfig c = SignalComposerConfig.defaults();
        assertFalse(c.enabled); assertTrue(c.valid); assertTrue(c.symbols.isEmpty());
        assertEquals(5000, c.normalTriggerSize); assertEquals(5000, c.normalConfirmationSize);
        assertEquals(3000, c.minimumTriggerSize); assertEquals(3000, c.observationFloorSize);
        assertEquals(120000, c.historyRetentionMs); assertEquals(30000, c.beforeWindowMs); assertEquals(30000, c.afterWindowMs);
        assertEquals(20, c.maxPriceDistanceTicks); assertEquals(2, c.directionalPriceToleranceTicks);
        assertEquals(20, c.maxTriggerDriftTicks); assertEquals(2048, c.maxEvents); assertEquals(64, c.maxCandidates);
        assertEquals(4096, c.maxWallPhases); assertEquals(8192, c.maxAttributionTrades);
    }
    @Test void defaultsDefineAllSixRequirementsWithoutScores() {
        SignalComposerConfig c = SignalComposerConfig.defaults();
        assertEquals(5000, c.triggerRequirements.none); assertEquals(5000, c.triggerRequirements.belowNormal);
        assertEquals(5000, c.triggerRequirements.normal); assertEquals(5000, c.triggerRequirements.strong);
        assertEquals(4000, c.triggerRequirements.veryStrong); assertEquals(3000, c.triggerRequirements.exceptional);
        assertEquals(1, c.strengthMultiples.normal); assertEquals(2, c.strengthMultiples.strong);
        assertEquals(5, c.strengthMultiples.veryStrong); assertEquals(10, c.strengthMultiples.exceptional);
    }
    @Test void detectorDefaultsRepresentObservedInteractions() {
        SignalComposerConfig.DetectorSettings d = SignalComposerConfig.defaults().detectors;
        assertEquals(500, d.wallLifetimeMs); assertEquals(500, d.clearDecisionMs);
        assertEquals(2000, d.attributionLookbackMs); assertEquals(.1, d.clearRemainingRatio);
        assertEquals(.7, d.consumptionRatio); assertEquals(.1, d.withdrawalMaxTradeRatio);
        assertEquals(500, d.movePairWindowMs); assertEquals(.1, d.moveSizeToleranceRatio);
        assertEquals(2, d.approachDistanceTicks); assertEquals(2, d.rejectionDistanceTicks);
        assertEquals(200, d.rejectionHoldMs); assertEquals(5000, d.interactionWindowMs);
        assertEquals(.25, d.growthRatio); assertEquals(1, d.breakoutDistanceTicks); assertEquals(3000, d.breakoutWindowMs);
        assertThrows(UnsupportedOperationException.class, () -> SignalComposerConfig.defaults().symbols.add("TEST"));
    }
}
