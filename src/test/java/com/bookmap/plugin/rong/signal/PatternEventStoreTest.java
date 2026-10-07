package com.bookmap.plugin.rong.signal;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.patterns.*;
import com.google.gson.JsonParser;

class PatternEventStoreTest {
    @Test void onlyBreakoutAndBreakdownAdmitOneThousandAndOtherTypesRequireThreeThousand() {
        for (PatternEventType type : PatternEventType.values()) {
            long minimum = type == PatternEventType.OFFER_BREAKOUT || type == PatternEventType.BID_BREAKDOWN ? 1000 : 3000;
            for (long size : new long[] {999, 1000, 2999, 3000}) {
                PatternEventStore store = new PatternEventStore("TEST", 1);
                PatternEvent event = PatternEvent.builder("TEST", 1, type, "wall")
                        .size(size, PatternEvent.SizeBasis.DISPLAYED_WALL).price(5100, .01).times(100, 100).build();
                assertEquals(size >= minimum ? PatternEventStore.Change.INSERTED : PatternEventStore.Change.REJECTED,
                        store.put(event, 100), type + " size=" + size);
                assertEquals(size >= minimum ? 1 : 0, store.size());
            }
        }
    }
    @Test void duplicateDeliveryStillPrunesAtItsNewProcessingTime() {
        PatternEventStore store = new PatternEventStore("TEST", 1);
        PatternEvent trigger = event("TEST", 1, "w", 1, 1_000_000L, 1_000_000L);
        store.put(trigger, trigger.observedAtNs);
        assertEquals(PatternEventStore.Change.DUPLICATE, store.put(trigger, 301_001_000_000L));
        assertEquals(0, store.size()); assertEquals(trigger.id, store.lastEvictions().get(0).id);
    }
    @Test void expiresOnMarketTimeEvenWithoutNewPatternsAndKeepsBoundary() {
        PatternEventStore store = new PatternEventStore("TEST", 1);
        PatternEvent trigger = event("TEST", 1, "w", 1, 1_000_000L, 1_000_000L);
        store.put(trigger, trigger.observedAtNs);
        assertTrue(store.prune(trigger.eventTimeNs + 300_000_000_000L).isEmpty());
        assertEquals(trigger.id, store.prune(trigger.eventTimeNs + 300_000_000_001L).get(0).id);
        assertEquals(0, store.size());
    }
    @Test void capacityEvictsOldestOccurrenceIncludingLateInsertedEvents() {
        SignalComposerConfig config = SignalComposerConfig.parse(JsonParser.parseString("{\"maxEvents\":2}").getAsJsonObject());
        PatternEventStore store = new PatternEventStore("TEST", 1, config);
        store.put(event("TEST", 1, "new1", 1, 200, 200), 200);
        store.put(event("TEST", 1, "new2", 1, 300, 300), 300);
        PatternEvent late = event("TEST", 1, "late", 1, 100, 400);
        store.put(late, 400);
        assertEquals(2, store.size()); assertEquals(late.id, store.lastEvictions().get(0).id);
        assertNull(store.get(late.id));
        assertThrows(UnsupportedOperationException.class, () -> store.lastEvictions().clear());
    }
    @Test void epochResetClearsHistoryAndClock() {
        PatternEventStore store = new PatternEventStore("TEST", 1);
        store.put(event("TEST", 1, "w", 1, 100, 100), 100);
        assertThrows(IllegalArgumentException.class, () -> store.prune(99));
        store.reset(2); assertEquals(0, store.size()); assertEquals(0, store.watermarkNs());
        assertEquals(PatternEventStore.Change.REJECTED, store.put(event("TEST", 1, "old", 1, 1, 1), 1));
        assertEquals(PatternEventStore.Change.INSERTED, store.put(event("TEST", 2, "new", 1, 1, 1), 1));
    }
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
