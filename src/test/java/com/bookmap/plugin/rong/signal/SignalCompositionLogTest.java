package com.bookmap.plugin.rong.signal;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.signal.CompositionModelsTest.event;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.patterns.*;

class SignalCompositionLogTest {
    @Test void firstValidationSummarizesOnceAndLateConfirmationIsDetailOnly() {
        List<String> summaries = new ArrayList<>(), details = new ArrayList<>();
        SignalCompositionLog log = new SignalCompositionLog("TEST", 2048, new SignalCompositionLog.Sink() {
            public void summary(String alias, String full, String concise) { summaries.add(concise); details.add(full); }
            public void detail(String alias, String full) { details.add(full); }
        });
        SignalComposer c = new SignalComposer("TEST", 1, SignalComposerConfig.defaults(), () -> 1000);
        PatternEvent bid = event(PatternEventType.BID_STEP_UP, 5000, 5105, 100, "b"); log.event(bid);
        CompositionUpdate first = c.onPatternEvent(bid); log.update(first); log.update(first); assertEquals(1, summaries.size());
        PatternEvent later = event(PatternEventType.OFFER_BREAKOUT, 60000, 5120, 200, "o"); log.event(later);
        log.update(c.onPatternEvent(later)); assertEquals(1, summaries.size());
        assertTrue(summaries.get(0).contains("Advisory LONG"));
        assertTrue(details.stream().anyMatch(line -> line.contains("revision=2") && line.contains("after validation")));
        assertTrue(details.stream().anyMatch(line -> line.contains("Semantic event") && line.contains("occurrenceNs=")));
        log.update(c.onMarketTime(30_000_000_201L)); log.update(c.reset(ResetReason.DISABLED, 2));
        assertTrue(details.stream().anyMatch(line -> line.contains("EXPIRED")));
        assertTrue(details.stream().anyMatch(line -> line.contains("Observation reset: DISABLED")));
        assertEquals(1, summaries.size());
    }
}
