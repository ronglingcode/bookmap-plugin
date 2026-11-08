package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.controllers.SelectedStop;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SelectedStopTest {
    @Test void expiryReplacementClearAndSymbolMismatchCannotReuseAStop() {
        AtomicLong time = new AtomicLong(); SelectedStop stop = new SelectedStop(time::get);
        assertThrows(IllegalArgumentException.class, () -> stop.consume("AAPL"));
        stop.select("AAPL", 99); time.set(SelectedStop.LIFETIME_NS - 1);
        assertEquals(99, stop.view().get("price").getAsDouble());
        time.incrementAndGet(); assertEquals(0, stop.view().get("price").getAsDouble());
        assertThrows(IllegalArgumentException.class, () -> stop.consume("AAPL"));
        stop.select("AAPL", 99); stop.select("AAPL", 98);
        assertEquals(98, stop.consume("AAPL").price);
        assertThrows(IllegalArgumentException.class, () -> stop.consume("AAPL"));
        stop.select("AAPL", 99);
        assertThrows(IllegalArgumentException.class, () -> stop.consume("MSFT"));
        assertThrows(IllegalArgumentException.class, () -> stop.consume("AAPL"));
        stop.select("AAPL", 99); stop.clear(); assertEquals(0, stop.view().get("price").getAsDouble());
    }
    @Test void queuedSubmissionCannotOutliveTheOriginalSelectionOrBeRefreshedByAnotherSelection() {
        AtomicLong time = new AtomicLong(); SelectedStop stop = new SelectedStop(time::get);
        stop.select("AAPL", 99); SelectedStop.Claim claim = stop.consume("AAPL");
        claim.requireFresh(); time.set(SelectedStop.LIFETIME_NS);
        stop.select("AAPL", 98);
        assertThrows(IllegalArgumentException.class, claim::requireFresh);
    }
    @Test void concurrentEntriesConsumeOnlyOnce() throws Exception {
        SelectedStop stop = new SelectedStop(); stop.select("AAPL", 99);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> enter = () -> { try { stop.consume("AAPL"); return true; } catch (IllegalArgumentException blocked) { return false; } };
            var results = pool.invokeAll(java.util.List.of(enter, enter));
            assertNotEquals(results.get(0).get(), results.get(1).get());
        } finally { pool.shutdownNow(); }
    }
}
