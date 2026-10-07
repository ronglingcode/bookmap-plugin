package com.bookmap.plugin.rong.signal;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.patterns.*;

class CompositionModelsTest {
    @Test void publishedResultsCannotCarryScoresOrExecutionCommands() {
        for (java.lang.reflect.Field field : TradingSignal.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(name.contains("score") || name.contains("order") || name.contains("quantity") || name.contains("command"));
            assertFalse(field.getType().getName().startsWith("velox."));
        }
    }
    static PatternEvent event(PatternEventType type, long size, int price, long timeNs, String interaction) {
        return PatternEvent.builder("TEST", 1, type, interaction).size(size, PatternEvent.SizeBasis.DISPLAYED_WALL)
                .price(price, .01).times(timeNs, timeNs)
                .evidence(PatternEvent.Evidence.builder().attribution(PatternEvent.Attribution.UNKNOWN, PatternEvent.Coverage.USABLE).build()).build();
    }
    @Test void validationAndOutputCopiesCannotBeRewritten() {
        PatternEvent bid = event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 20_000_000L, "bid");
        PatternEvent offer = event(PatternEventType.OFFER_HOLD, 60000, 5120, 10_000_000L, "offer");
        ConfirmationMatch match = new ConfirmationMatch(bid, offer);
        List<ConfirmationMatch> source = new ArrayList<>(); source.add(match);
        TradingSignal.FirstValidation first = new TradingSignal.FirstValidation(20_000_000L, 5000, 3000, ConfirmationStrength.EXCEPTIONAL, source, match);
        source.clear(); assertEquals(1, first.confirmations.size());
        TradingSignal result = new TradingSignal(bid, first, source, Collections.singletonList(bid), ConfirmationStrength.EXCEPTIONAL, 1, 1, "rules", "reason");
        source.add(match); assertTrue(result.subsequentConfirmations.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.confirmations.clear());
        assertEquals(Direction.SHORT, result.direction); assertEquals(-10, match.timeDeltaMs);
        assertEquals(ConfirmationMatch.Ordering.BEFORE, match.ordering); assertEquals(15, match.priceDistanceTicks);
    }
    @Test void preservesNanosecondOrderingAndRejectsFutureEvidence() {
        PatternEvent bid = event(PatternEventType.BID_STEP_UP, 5000, 5105, 100, "bid");
        PatternEvent offer = event(PatternEventType.OFFER_BREAKOUT, 60000, 5107, 101, "offer");
        ConfirmationMatch match = new ConfirmationMatch(bid, offer);
        assertEquals(0, match.timeDeltaMs); assertEquals(ConfirmationMatch.Ordering.AFTER, match.ordering);
        TradingSignal.FirstValidation invalid = new TradingSignal.FirstValidation(100, 5000, 3000, ConfirmationStrength.EXCEPTIONAL, Collections.singletonList(match), match);
        assertThrows(IllegalArgumentException.class, () -> new TradingSignal(bid, invalid, Collections.emptyList(), Collections.emptyList(), ConfirmationStrength.EXCEPTIONAL, 1, 1, "r", "x"));
    }
    @Test void candidateRejectsOfferOnlyAndContextsRemainSeparateFromSignals() {
        PatternEvent offer = event(PatternEventType.OFFER_HOLD, 60000, 5120, 100, "offer");
        assertThrows(IllegalArgumentException.class, () -> new SignalCandidate(offer, 30000));
        DevelopingContext context = new DevelopingContext(Direction.SHORT, offer, ConfirmationStrength.EXCEPTIONAL, 3000, 200);
        Map<Direction, DevelopingContext> source = new EnumMap<>(Direction.class); source.put(Direction.SHORT, context);
        CompositionUpdate update = new CompositionUpdate(Collections.emptyList(), source, Collections.emptyList(), Collections.emptyList(), 100);
        source.clear(); assertEquals(PatternMeaning.BID_FAIL, update.contexts.get(Direction.SHORT).waitingFor);
        assertTrue(update.signals.isEmpty()); assertThrows(UnsupportedOperationException.class, () -> update.contexts.clear());
    }
}
