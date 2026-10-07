package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import static com.bookmap.plugin.rong.patterns.PatternObservationEngineTest.*;

class WallInteractionDetectorTest {
    @Test void quoteAtOrAboveOfferDisarmsAnIncompleteRejection() {
        Fixture f = qualified(); f.trade(5119, 1, true, 2600); f.trade(5113, 1, false, 2700);
        f.engine.onBbo(5120, 5121, BASE + 2_800_000_000L, MARKET); f.trade(5113, 1, false, 3300);
        assertTrue(f.events.isEmpty());
    }
    @Test void consumedOfferNeedsAbovePrintAndUsesMeasuredRemovedSize() {
        Fixture f = qualified(); f.trade(5120, 4200, true, 2900); f.depth(false, 5120, 0, 3000); f.time(3500);
        assertTrue(f.events.isEmpty()); f.trade(5120, 1, true, 3600); assertTrue(f.events.isEmpty());
        f.trade(5121, 1, true, 3700);
        PatternEvent e = f.events.get(0); assertEquals(PatternEventType.OFFER_BREAKOUT, e.type);
        assertEquals(PatternMeaning.OFFER_BULLISH_CONFIRMATION, e.meaning); assertEquals(6000, e.size);
        assertEquals(PatternEvent.SizeBasis.DISPLAYED_REMOVED, e.sizeBasis);
        assertEquals(4200, e.evidence.attributedTradeSize); assertEquals(BASE + 3_700_000_000L, e.eventTimeNs);
        Fixture early = qualified(); early.trade(5120, 6000, true, 2900); early.depth(false, 5120, 0, 3000);
        early.trade(5121, 1, true, 3100); early.time(3500);
        assertEquals(BASE + 3_100_000_000L, early.events.get(0).eventTimeNs);
    }
    @Test void offerPullUnknownAttributionExpiredAndWrongDirectionCannotConfirmBullish() {
        Fixture pull = qualified(); pull.depth(false, 5120, 0, 3000); pull.time(3500); pull.trade(5121, 1, true, 3600);
        assertTrue(pull.events.isEmpty());
        Fixture unknown = qualified(); unknown.trade(5120, 6000, null, 2900); unknown.depth(false, 5120, 0, 3000);
        unknown.time(3500); unknown.trade(5121, 1, true, 3600); assertTrue(unknown.events.isEmpty());
        Fixture expired = qualified(); expired.trade(5120, 6000, true, 2900); expired.depth(false, 5120, 0, 3000);
        expired.time(3500); expired.trade(5121, 1, true, 6001); assertTrue(expired.events.isEmpty());
        Fixture wrong = qualified(); wrong.trade(5120, 6000, true, 2900); wrong.depth(false, 5120, 0, 3000);
        wrong.time(3500); wrong.trade(5119, 1, false, 3600); assertTrue(wrong.events.isEmpty());
    }
    @Test void growthAloneIsUnknownAndGrowthThenHoldProducesOneCompositeConfirmation() {
        Fixture f = new Fixture(); f.depth(false, 5120, 48000, 2000); f.time(2500);
        f.depth(false, 5120, 60000, 2600);
        assertEquals(PatternEventType.OFFER_SIZE_INCREASE, f.events.get(0).type);
        assertEquals(PatternMeaning.UNKNOWN, f.events.get(0).meaning);
        f.trade(5119, 1, true, 2700); f.trade(5113, 1, false, 2800); f.time(3300);
        assertEquals(1, f.events.size()); // elapsed time alone does not confirm
        f.trade(5113, 1, false, 3300);
        PatternEvent composite = f.events.get(1);
        assertEquals(PatternEventType.OFFER_SIZE_INCREASING_HOLD, composite.type);
        assertEquals(60000, composite.size); assertEquals(48000, composite.evidence.growthBaselineSize);
        f.trade(5112, 1, false, 3400); assertEquals(2, f.events.size());
    }
    @Test void laterGrowthRequiresNewRetreatConfirmationAndUpgradesTheSameEpisode() {
        Fixture f = qualified(); f.trade(5119, 1, true, 2600); f.trade(5113, 1, false, 2700);
        f.trade(5113, 1, false, 3200); PatternEvent initial = f.events.get(0);
        f.depth(false, 5120, 7500, 3300); assertEquals(2, f.events.size());
        f.time(3900); assertEquals(2, f.events.size());
        f.trade(5113, 1, false, 4000); f.trade(5113, 1, false, 4500);
        PatternEvent upgrade = f.events.get(2);
        assertEquals(initial.id, upgrade.id); assertEquals(initial.interactionId, upgrade.interactionId);
        assertEquals(initial.eventTimeNs, upgrade.eventTimeNs); assertEquals(2, upgrade.revision);
        assertEquals(PatternEventType.OFFER_SIZE_INCREASING_HOLD, upgrade.type);
        assertTrue(upgrade.observedAtNs > initial.observedAtNs);
    }
    private Fixture qualified() {
        Fixture f = new Fixture(); f.depth(false, 5120, 6000, 2000); f.time(2500); return f;
    }
}
