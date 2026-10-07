package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PatternEventTest {
    private PatternEvent.Builder event() {
        return PatternEvent.builder("TEST", 1, PatternEventType.BIDS_CANCELLED, "wall:1")
                .size(3000, PatternEvent.SizeBasis.DISPLAYED_REMOVED).price(5105, .01)
                .times(1_000_000_001L, 1_500_000_001L);
    }
    @Test void separatesOccurrenceDetectionAndStableRevisionIdentity() {
        PatternEvent first = event().build(), revised = event().revision(2).build();
        assertEquals(first.id, revised.id);
        assertEquals(first.eventTimeNs, revised.eventTimeNs);
        assertEquals(2, revised.revision);
        assertEquals(51.05, first.price, .000001);
        assertEquals(1000, first.eventTimeMs());
        assertEquals(1500, first.observedAtMs());
    }
    @Test void rejectsInvalidIdentityClassificationAndMeasurements() {
        assertThrows(IllegalArgumentException.class, () -> event().classification(PatternSide.OFFER, PatternMeaning.BID_FAIL).build());
        assertThrows(IllegalArgumentException.class, () -> event().classification(PatternSide.BID, PatternMeaning.BID_HOLD).build());
        assertThrows(IllegalArgumentException.class, () -> event().size(0, PatternEvent.SizeBasis.DISPLAYED_REMOVED).build());
        assertThrows(IllegalArgumentException.class, () -> event().price(0, .01).build());
        assertThrows(IllegalArgumentException.class, () -> event().price(5105, Double.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> event().times(100, 99).build());
        assertThrows(IllegalArgumentException.class, () -> event().revision(0).build());
        assertFalse(PatternMeaning.BID_FAIL.isCompatible(PatternSide.OFFER));
        assertTrue(PatternMeaning.UNKNOWN.isCompatible(PatternSide.OFFER));
    }
    @Test void defensivelyCopiesEvidenceAndPreservesUnknownSide() {
        Map<String, String> source = new HashMap<>(); source.put("note", "original");
        PatternEvent.Evidence evidence = PatternEvent.Evidence.builder().wall("w", 3000, 0)
                .trades(0, null).metadata(source).build();
        source.put("note", "changed");
        assertEquals("original", evidence.metadata.get("note"));
        assertThrows(UnsupportedOperationException.class, () -> evidence.metadata.put("x", "y"));
        assertNull(evidence.buyAggressor);
        assertEquals(PatternEvent.Attribution.UNKNOWN, evidence.attribution);
        assertEquals(PatternEvent.Coverage.WARMUP, evidence.coverage);
        assertEquals(3000, evidence.removedSize);
        assertThrows(IllegalArgumentException.class, () -> PatternEvent.Evidence.builder().trades(-1, null).build());
    }
    @Test void compositeUpgradeCanPreserveOneEpisodeIdentity() {
        PatternEvent rejection = PatternEvent.builder("TEST", 1, PatternEventType.OFFER_HOLD, "w")
                .episodeKey("offer-interaction:w:1").size(60000, PatternEvent.SizeBasis.DISPLAYED_WALL)
                .price(5120, .01).times(100, 100).build();
        PatternEvent composite = PatternEvent.builder("TEST", 1, PatternEventType.OFFER_SIZE_INCREASING_HOLD, "w")
                .episodeKey(rejection.episodeKey).revision(2).size(60000, PatternEvent.SizeBasis.DISPLAYED_WALL)
                .price(5120, .01).times(100, 100).build();
        assertEquals(rejection.id, composite.id);
    }
}
