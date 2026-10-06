package com.bookmap.plugin.rong.signal;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.patterns.*;

class PatternEventStoreTest {
    private PatternEvent event(String alias, long epoch, String interaction, int revision, long occurrence, long observed) {
        return PatternEvent.builder(alias, epoch, PatternEventType.BID_STEP_UP, interaction).revision(revision)
                .size(5000, PatternEvent.SizeBasis.DISPLAYED_WALL).price(5105, .01).times(occurrence, observed).build();
    }
    @Test void delayedOccurrenceIsOrderedWithoutResettingProcessingTime() {
        PatternEventStore store = new PatternEventStore("TEST", 1);
        PatternEvent first = event("TEST", 1, "first", 1, 200, 200);
        PatternEvent late = event("TEST", 1, "late", 1, 100, 300);
        assertEquals(PatternEventStore.Change.INSERTED, store.put(first, 200));
        assertEquals(PatternEventStore.Change.INSERTED, store.put(late, 300));
        assertEquals(late.id, store.snapshot().get(0).id);
        assertEquals(1, store.epoch()); assertEquals(300, store.watermarkNs());
        assertThrows(UnsupportedOperationException.class, () -> store.snapshot().clear());
    }
    @Test void revisionsReplaceOneEpisodeAndCannotChangeOriginalOccurrence() {
        PatternEventStore store = new PatternEventStore("TEST", 1);
        PatternEvent first = event("TEST", 1, "w", 1, 100, 100);
        assertEquals(PatternEventStore.Change.INSERTED, store.put(first, 100));
        assertEquals(PatternEventStore.Change.DUPLICATE, store.put(first, 100));
        PatternEvent updated = event("TEST", 1, "w", 2, 100, 200);
        assertEquals(PatternEventStore.Change.REVISED, store.put(updated, 200));
        assertEquals(1, store.size()); assertSame(updated, store.getEpisode(first.episodeKey));
        assertEquals(PatternEventStore.Change.DUPLICATE, store.put(first, 200));
        assertEquals(PatternEventStore.Change.REJECTED, store.put(event("TEST", 1, "w", 3, 150, 200), 200));
    }
    @Test void rejectsAnotherAliasEpochAndUnobservedEvidence() {
        PatternEventStore store = new PatternEventStore("TEST", 1);
        assertEquals(PatternEventStore.Change.REJECTED, store.put(event("OTHER", 1, "w", 1, 100, 100), 100));
        assertEquals(PatternEventStore.Change.REJECTED, store.put(event("TEST", 2, "w", 1, 100, 100), 100));
        assertEquals(PatternEventStore.Change.REJECTED, store.put(event("TEST", 1, "w", 1, 100, 200), 100));
        assertEquals(0, store.size()); assertEquals(0, store.watermarkNs());
    }
}
