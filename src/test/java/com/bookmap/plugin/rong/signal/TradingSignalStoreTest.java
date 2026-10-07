package com.bookmap.plugin.rong.signal;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.signal.CompositionModelsTest.event;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.patterns.*;

class TradingSignalStoreTest {
    @Test void revisionKeepsOriginalTtlAndContextsUseMarketTime() {
        AtomicLong receipt = new AtomicLong(1000); TradingSignalStore store = new TradingSignalStore(receipt::get);
        SignalComposer c = new SignalComposer("TEST", 1, SignalComposerConfig.defaults(), receipt::get);
        CompositionUpdate initial = c.onPatternEvent(event(PatternEventType.BID_BREAKDOWN, 5000, 5105, 100, "b"));
        store.publish("TEST", 1, initial); receipt.set(2000);
        store.publish("TEST", 1, c.onPatternEvent(event(PatternEventType.OFFER_HOLD, 60000, 5120, 200, "o")));
        assertEquals(2, store.snapshot("TEST").signals.get(0).revision);
        receipt.set(31000); TradingSignalStore.Snapshot expired = store.snapshot("TEST");
        assertTrue(expired.signals.isEmpty()); assertEquals(1, expired.contexts.size()); assertEquals(200, expired.marketTimeNs);
        store.publish("TEST", 1, c.onMarketTime(300_000_000_201L)); assertTrue(store.snapshot("TEST").contexts.isEmpty());
        store.publish("TEST", 1, initial); assertTrue(store.snapshot("TEST").signals.isEmpty());
    }
    @Test void capEpochAliasAndListenersAreIndependent() {
        TradingSignalStore store = new TradingSignalStore(() -> 1000); AtomicInteger notifications = new AtomicInteger();
        java.util.function.Consumer<String> listener = alias -> { store.snapshot(alias); notifications.incrementAndGet(); };
        store.addListener(listener); SignalComposer c = new SignalComposer("TEST", 1, SignalComposerConfig.defaults(), () -> 1000);
        for (int i = 1; i <= 21; i++) store.publish("TEST", 1, c.onPatternEvent(event(PatternEventType.BID_STEP_UP, 5000, 5105, i, "b" + i)));
        assertEquals(20, store.snapshot("TEST").signals.size()); assertTrue(store.snapshot("OTHER").signals.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> store.snapshot("TEST").signals.clear());
        store.clear("TEST", 2); assertTrue(store.snapshot("TEST").signals.isEmpty());
        store.publish("TEST", 1, c.onMarketTime(100)); assertEquals(2, store.snapshot("TEST").epoch);
        store.removeListener(listener); int before = notifications.get(); store.removeAlias("TEST"); assertEquals(before, notifications.get());
    }
    @Test void concurrentReadsSeeImmutableSnapshots() {
        TradingSignalStore store = new TradingSignalStore(() -> 1000);
        SignalComposer c = new SignalComposer("TEST", 1, SignalComposerConfig.defaults(), () -> 1000);
        CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < 1000; i++) for (TradingSignal signal : store.snapshot("TEST").signals) assertEquals(1, signal.revision);
        });
        for (int i = 1; i <= 64; i++) store.publish("TEST", 1, c.onPatternEvent(event(PatternEventType.BID_STEP_UP, 5000, 5105, i, "b" + i)));
        reader.join(); assertEquals(20, store.snapshot("TEST").signals.size());
    }
}
