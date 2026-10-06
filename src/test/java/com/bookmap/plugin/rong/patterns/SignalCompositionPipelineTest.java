package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.patterns.PatternObservationEngineTest.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.signal.*;
import com.bookmap.plugin.rong.OrderBookState;

class SignalCompositionPipelineTest {
    private static class Harness {
        final List<TradingSignal> signals = new ArrayList<>();
        final List<PatternEvent> events = new ArrayList<>();
        final OrderBookState book = new OrderBookState();
        CompositionUpdate latest;
        final SignalCompositionPipeline pipeline;
        Harness(long receipt) {
            pipeline = new SignalCompositionPipeline("TEST", .01, SignalComposerConfig.defaults(), update -> {
                latest = update; signals.addAll(update.signals);
            }, events::add, diagnostic -> {}, () -> receipt);
            pipeline.markReady(); time(0); pipeline.onBbo(5100, 5121, BASE, MARKET);
            for (int price = 4900; price < 5000; price++) book.update(true, price, 100000);
        }
        void time(long ms) { pipeline.onTimestamp(BASE + ms * 1_000_000L, MARKET); }
        void depth(boolean bid, int price, long size, long ms) {
            book.update(bid, price, (int)size); pipeline.onDepth(bid, price, size, BASE + ms * 1_000_000L, MARKET);
        }
        void trade(int price, long size, Boolean buy, long ms) { pipeline.onTrade(price, size, buy, BASE + ms * 1_000_000L, MARKET); }
    }
    private Harness shortExample(long receipt) {
        Harness h = new Harness(receipt);
        h.depth(false, 5120, 48000, 2000); h.depth(true, 5105, 3000, 2000); h.time(2500);
        h.depth(false, 5120, 60000, 2600); h.trade(5119, 1, true, 2700); h.trade(5118, 1, false, 2800); h.time(3000);
        assertTrue(h.signals.isEmpty()); assertEquals(3000, h.latest.contexts.get(Direction.SHORT).requiredTriggerSize);
        h.depth(true, 5105, 0, 3100); h.time(3600); return h;
    }
    @Test void rawSixtyThousandGrowthRejectionAndThreeThousandWithdrawalProduceOneShort() {
        Harness h = shortExample(1000); assertEquals(1, h.signals.size());
        assertTrue(h.book.getSizeThreshold(5000, 97) > 5000);
        TradingSignal signal = h.signals.get(0); assertEquals(Direction.SHORT, signal.direction);
        assertEquals(3000, signal.trigger.size); assertEquals(3000, signal.firstValidation.appliedTriggerThreshold);
        assertEquals(ConfirmationStrength.EXCEPTIONAL, signal.firstValidation.confirmationStrength);
        assertTrue(signal.explanation.contains("60K")); assertTrue(signal.explanation.contains("inferred"));
        Harness otherSpeed = shortExample(999999);
        assertEquals(signal.explanation, otherSpeed.signals.get(0).explanation);
        assertEquals(signal.firstValidation.eventTimeNs, otherSpeed.signals.get(0).firstValidation.eventTimeNs);
    }
    @Test void rawBidHoldAndConsumedOfferBreakoutProduceLongWithOneCanonicalSignal() {
        Harness h = new Harness(1000); h.trade(5090, 1, false, 100);
        h.depth(true, 5100, 3000, 2000); h.depth(false, 5120, 60000, 2000); h.time(2500);
        h.depth(true, 5101, 3000, 2600); h.time(3100); assertTrue(h.signals.isEmpty());
        h.trade(5120, 42000, true, 3200); h.depth(false, 5120, 0, 3300); h.time(3800); assertTrue(h.signals.isEmpty());
        h.trade(5121, 1, true, 3900); assertEquals(1, h.signals.size());
        assertEquals(Direction.LONG, h.signals.get(0).direction);
        assertEquals(PatternEventType.BID_STEP_UP, h.signals.get(0).trigger.type);
        assertEquals(PatternEventType.OFFER_BREAKOUT, h.signals.get(0).firstValidation.strongestConfirmation.event.type);
    }
    @Test void offerOnlyAndFallbackNeverCreateSignalsAndSeekClearsContext() {
        Harness h = new Harness(1000); h.depth(false, 5120, 48000, 2000); h.time(2500);
        h.depth(false, 5120, 60000, 2600); h.trade(5119, 1, true, 2700); h.trade(5118, 1, false, 2800); h.time(3000);
        assertTrue(h.signals.isEmpty()); assertFalse(h.latest.contexts.isEmpty());
        h.pipeline.onDepth(true, 5105, 3000, BASE + 3_100_000_000L, PatternEvent.TimestampProvenance.FALLBACK);
        h.pipeline.onDepth(true, 5105, 0, BASE + 3_700_000_000L, PatternEvent.TimestampProvenance.FALLBACK);
        h.time(4000); assertTrue(h.signals.isEmpty());
        h.time(1000); assertTrue(h.latest.contexts.isEmpty()); assertFalse(h.pipeline.usable());
    }
}
