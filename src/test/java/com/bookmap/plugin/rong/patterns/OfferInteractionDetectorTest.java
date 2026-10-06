package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import static com.bookmap.plugin.rong.patterns.PatternObservationEngineTest.*;

class OfferInteractionDetectorTest {
    private Fixture qualified() {
        Fixture f = new Fixture(); f.depth(false, 5120, 6000, 2000); f.time(2500); return f;
    }
    @Test void approachSubsequentRejectionAndInclusiveHoldEmitCurrentPersistentSize() {
        Fixture f = qualified(); f.trade(5119, 1, true, 2600); f.trade(5118, 1, false, 2700);
        f.time(2899); assertTrue(f.events.isEmpty()); f.depth(false, 5120, 7000, 2900);
        PatternEvent e = f.events.get(0); assertEquals(PatternEventType.OFFER_REJECTION, e.type);
        assertEquals(7000, e.size); assertEquals(PatternMeaning.OFFER_BEARISH_CONFIRMATION, e.meaning);
        assertEquals(BASE + 2_600_000_000L, e.evidence.approachTimeNs);
        assertEquals(BASE + 2_700_000_000L, e.evidence.rejectionTimeNs);
        f.time(3000); assertEquals(1, f.events.size());
    }
    @Test void distantIncompleteExpiredRemovedAndBrokenOffersCannotReject() {
        Fixture distant = qualified(); distant.trade(5110, 1, false, 2600); distant.time(3000); assertTrue(distant.events.isEmpty());
        Fixture incomplete = qualified(); incomplete.trade(5119, 1, true, 2600); incomplete.time(3000); assertTrue(incomplete.events.isEmpty());
        Fixture expired = qualified(); expired.trade(5119, 1, true, 2600); expired.trade(5118, 1, false, 7601); expired.time(7900);
        assertTrue(expired.events.isEmpty());
        Fixture removed = qualified(); removed.trade(5119, 1, true, 2600); removed.trade(5118, 1, false, 2700);
        removed.depth(false, 5120, 0, 2800); removed.time(3000); assertTrue(removed.events.isEmpty());
        Fixture broken = qualified(); broken.trade(5119, 1, true, 2600); broken.trade(5118, 1, false, 2700);
        broken.trade(5121, 1, true, 2800); broken.time(3000); assertTrue(broken.events.isEmpty());
    }
    @Test void returnNearOfferResetsHoldAndNewApproachHasNewInteraction() {
        Fixture f = qualified(); f.trade(5119, 1, true, 2600); f.trade(5118, 1, false, 2700);
        f.trade(5119, 1, true, 2800); f.time(3000); assertTrue(f.events.isEmpty());
        f.trade(5118, 1, false, 3100); f.time(3300); assertEquals(1, f.events.size());
        f.trade(5116, 1, false, 3400); f.trade(5119, 1, true, 3500); f.trade(5118, 1, false, 3600); f.time(3800);
        assertEquals(2, f.events.size()); assertNotEquals(f.events.get(0).interactionId, f.events.get(1).interactionId);
    }
}
