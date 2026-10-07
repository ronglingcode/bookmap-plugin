package com.bookmap.plugin.rong.signal;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;

class SignalComposerConfigTest {
    private SignalComposerConfig parse(String value) { return SignalComposerConfig.parse(JsonParser.parseString(value).getAsJsonObject()); }
    @Test void parsesCustomTypedValuesAndRoundTripsExplicitKeys() {
        SignalComposerConfig c = parse("{\"enabled\":true,\"symbols\":[\"aapl:NASDAQ@BMD\"],\"normalConfirmationSize\":6000,\"detectors\":{\"growthRatio\":0.5}}");
        assertTrue(c.valid, c.error); assertTrue(c.enabled); assertTrue(c.eligible("AAPL")); assertFalse(c.eligible("MSFT"));
        assertEquals(6000, c.normalConfirmationSize); assertEquals(5000, c.normalTriggerSize); assertEquals(.5, c.detectors.growthRatio);
        assertEquals(c.revision, SignalComposerConfig.parse(c.toJson()).revision);
        assertNotEquals(c.revision, SignalComposerConfig.defaults().revision);
    }
    @Test void invalidSettingsAreDisabledWithAnActionableReason() {
        String[] invalid = {"{\"enabled\":\"true\"}", "{\"maxEvents\":1.5}", "{\"normalTriggerSize\":0}",
                "{\"normalTriggerSize\":6000}", "{\"minimumTriggerSize\":2000}",
                "{\"strengthMultiples\":{\"strong\":0.5}}", "{\"triggerRequirements\":{\"exceptional\":2000}}",
                "{\"beforeWindowMs\":300001}", "{\"detectors\":{\"consumptionRatio\":2}}",
                "{\"detectors\":{\"withdrawalMaxTradeRatio\":0.8}}", "{\"maxCandidates\":1000000}",
                "{\"symbols\":[1]}", "{\"symbols\":null}", "{\"detectors\":false}", "{\"maxEvents\":\"12\"}"};
        for (String input : invalid) {
            SignalComposerConfig c = parse(input); assertFalse(c.valid, input); assertFalse(c.enabled, input);
            assertTrue(c.error.startsWith("SignalComposer disabled:"));
        }
    }
    @Test void normalizedSymbolOrderDoesNotChangeRevision() {
        assertEquals(parse("{\"symbols\":[\"MSFT\",\"AAPL\"]}").revision,
                parse("{\"symbols\":[\"aapl\",\"msft\"]}").revision);
        assertFalse(SignalComposerConfig.parse((JsonObject)null).valid);
    }
    @Test void localRetentionOverridesCannotChangeCodeOwnedRetentionOrRevision() {
        for (String value : new String[] {"120000", "600000", "null", "\"invalid\""}) {
            SignalComposerConfig c = parse("{\"historyRetentionMs\":" + value + "}");
            assertTrue(c.valid, c.error);
            assertEquals(300000, c.historyRetentionMs);
            assertEquals(SignalComposerConfig.defaults().revision, c.revision);
        }
    }
    @Test void defaultsKeepTheDetectionFloorIndependentAndEnabled() {
        SignalComposerConfig c = SignalComposerConfig.defaults();
        assertTrue(c.enabled); assertTrue(c.valid); assertTrue(c.symbols.isEmpty());
        assertEquals(5000, c.normalTriggerSize); assertEquals(5000, c.normalConfirmationSize);
        assertEquals(3000, c.minimumTriggerSize); assertEquals(1000, c.observationFloorSize);
        assertEquals(300000, c.historyRetentionMs); assertEquals(300000, c.beforeWindowMs); assertEquals(300000, c.afterWindowMs);
        assertEquals(20, c.maxPriceDistanceTicks); assertEquals(2, c.directionalPriceToleranceTicks);
        assertEquals(20, c.maxTriggerDriftTicks); assertEquals(2048, c.maxEvents); assertEquals(64, c.maxCandidates);
        assertEquals(4096, c.maxWallPhases); assertEquals(8192, c.maxAttributionTrades);
    }
    @Test void explicitDisableOverridesEnabledDefault() {
        SignalComposerConfig c = parse("{\"enabled\":false}");
        assertTrue(c.valid); assertFalse(c.enabled);
    }
    @Test void localObservationFloorsCannotOverrideCodeOwnedTrackingThreshold() {
        for (String value : new String[] {"3000", "5000", "null", "\"invalid\""}) {
            SignalComposerConfig c = parse("{\"observationFloorSize\":" + value + "}");
            assertTrue(c.valid, c.error);
            assertEquals(1000, c.observationFloorSize);
            assertEquals(SignalComposerConfig.defaults().revision, c.revision);
        }
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
        assertEquals(.0005, d.holdApproachRatio); assertEquals(.001, d.holdRetreatRatio);
        assertEquals(500, d.holdConfirmationMs); assertEquals(15000, d.interactionWindowMs);
        assertEquals(.25, d.growthRatio); assertEquals(1, d.breakoutDistanceTicks); assertEquals(3000, d.breakoutWindowMs);
        assertThrows(UnsupportedOperationException.class, () -> SignalComposerConfig.defaults().symbols.add("TEST"));
    }
    @Test void percentageHoldSettingsRoundTripAndRejectInvalidValues() {
        JsonObject json = new JsonObject(), detectors = new JsonObject();
        detectors.addProperty("holdApproachRatio", .0008);
        detectors.addProperty("holdRetreatRatio", .002);
        detectors.addProperty("holdConfirmationMs", 700);
        json.add("detectors", detectors);
        SignalComposerConfig c = SignalComposerConfig.parse(json);
        assertTrue(c.valid, c.error);
        assertEquals(.0008, c.detectors.holdApproachRatio);
        assertEquals(.002, c.detectors.holdRetreatRatio);
        assertEquals(700, c.detectors.holdConfirmationMs);
        assertEquals(c.revision, SignalComposerConfig.parse(c.toJson()).revision);
        assertFalse(c.toJson().getAsJsonObject("detectors").has("approachDistanceTicks"));
        for (String key : new String[] {"holdApproachRatio", "holdRetreatRatio"}) {
            for (double value : new double[] {0, -.01, 1, 2}) {
                JsonObject invalid = c.toJson(); invalid.getAsJsonObject("detectors").addProperty(key, value);
                assertFalse(SignalComposerConfig.parse(invalid).valid, key + "=" + value);
            }
        }
        JsonObject invalid = c.toJson(); invalid.getAsJsonObject("detectors").addProperty("holdConfirmationMs", 15001);
        assertFalse(SignalComposerConfig.parse(invalid).valid);
    }
    @Test void legacyTickHoldKeysDoNotChangePercentageDefaults() {
        JsonObject json = new JsonObject(), detectors = new JsonObject();
        detectors.addProperty("approachDistanceTicks", 2);
        detectors.addProperty("rejectionDistanceTicks", 2);
        detectors.addProperty("rejectionHoldMs", 200);
        json.add("detectors", detectors);
        assertEquals(SignalComposerConfig.defaults().revision, SignalComposerConfig.parse(json).revision);
    }

}
