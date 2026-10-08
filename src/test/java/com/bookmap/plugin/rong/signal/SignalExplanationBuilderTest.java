package com.bookmap.plugin.rong.signal;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.signal.CompositionModelsTest.event;
import java.util.List;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.patterns.*;

class SignalExplanationBuilderTest {
    private final SignalExplanationBuilder builder = new SignalExplanationBuilder();
    @Test void explainsSmallBidTriggerAndExceptionalPriorOfferEvidence() {
        PatternEvent bid = event(PatternEventType.BIDS_CANCELLED, 3200, 5105, 20_000_000_000L, "b");
        ConfirmationMatch offer = new ConfirmationMatch(bid, event(PatternEventType.OFFER_SIZE_INCREASING_HOLD, 60000, 5120, 13_000_000_000L, "o"));
        TradingSignal.FirstValidation validation = new TradingSignal.FirstValidation(bid.eventTimeNs, 5000, 3000, ConfirmationStrength.EXCEPTIONAL, List.of(offer), offer);
        String text = builder.build(bid, validation, Collections.emptyList());
        assertTrue(text.contains("3.2K (3200 shares) @ 51.05")); assertTrue(text.contains("60K (60000 shares) @ 51.2"));
        assertTrue(text.contains("Applied trigger threshold: 3K")); assertTrue(text.contains("BEFORE"));
        assertTrue(text.contains("-7000 ms")); assertTrue(text.contains("15 ticks")); assertTrue(text.contains("cancellation is inferred"));
        assertEquals(text, builder.build(bid, validation, Collections.emptyList()));
    }
    @Test void noOfferStillExplainsNormalBidDefenseWithoutClaimingAbsorption() {
        PatternEvent bid = event(PatternEventType.BID_STEP_UP, 5000, 5105, 100, "b");
        TradingSignal.FirstValidation validation = new TradingSignal.FirstValidation(100, 5000, 5000, ConfirmationStrength.NONE, Collections.emptyList(), null);
        String text = builder.build(bid, validation, Collections.emptyList());
        assertTrue(text.startsWith("LONG SIGNAL")); assertTrue(text.contains("NONE"));
        assertTrue(text.contains("offer confirmation is optional")); assertTrue(text.contains("not proof of executed absorption"));
    }
    @Test void laterEvidenceCannotRewriteAcceptanceAndExactSizesRemainVisible() {
        PatternEvent bid = event(PatternEventType.BID_BREAKDOWN, 5000, 5105, 100, "b");
        TradingSignal.FirstValidation validation = new TradingSignal.FirstValidation(100, 5000, 5000, ConfirmationStrength.NONE, Collections.emptyList(), null);
        ConfirmationMatch after = new ConfirmationMatch(bid, event(PatternEventType.OFFER_BOUNCE, 60000, 5120, 200, "o"));
        String text = builder.build(bid, validation, List.of(after));
        assertTrue(text.contains("observed after validation")); assertTrue(text.contains("Applied trigger threshold: 5K"));
        assertTrue(text.contains("First validation time and applied threshold are unchanged"));
        assertTrue(SignalExplanationBuilder.size(2999).contains("2999 shares"));
    }
}
