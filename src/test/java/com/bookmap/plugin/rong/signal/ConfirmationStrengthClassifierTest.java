package com.bookmap.plugin.rong.signal;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.signal.CompositionModelsTest.event;
import java.util.List;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import com.google.gson.JsonParser;
import com.bookmap.plugin.rong.patterns.*;

class ConfirmationStrengthClassifierTest {
    private final SignalComposerConfig config = SignalComposerConfig.defaults();
    private final ConfirmationStrengthClassifier classifier = new ConfirmationStrengthClassifier(config);
    @Test void classifiesExactSizeBoundariesWithoutSumming() {
        long[] sizes = {0, 4999, 5000, 9999, 10000, 24999, 25000, 49999, 50000, Long.MAX_VALUE};
        ConfirmationStrength[] expected = {ConfirmationStrength.NONE, ConfirmationStrength.BELOW_NORMAL, ConfirmationStrength.NORMAL,
                ConfirmationStrength.NORMAL, ConfirmationStrength.STRONG, ConfirmationStrength.STRONG, ConfirmationStrength.VERY_STRONG,
                ConfirmationStrength.VERY_STRONG, ConfirmationStrength.EXCEPTIONAL, ConfirmationStrength.EXCEPTIONAL};
        for (int i = 0; i < sizes.length; i++) assertEquals(expected[i], classifier.classify(sizes[i]));
        PatternEvent bid = event(PatternEventType.BID_STEP_UP, 3000, 5105, 100, "b");
        ConfirmationMatch normal = new ConfirmationMatch(bid, event(PatternEventType.OFFER_BREAKOUT, 7000, 5110, 100, "o"));
        assertEquals(ConfirmationStrength.NORMAL, classifier.select(List.of(normal, normal, normal, normal, normal, normal, normal, normal)).strength);
    }
    @Test void avoidsRoundingHugeQuantitiesAcrossABand() {
        SignalComposerConfig large = SignalComposerConfig.parse(JsonParser.parseString("{\"normalConfirmationSize\":9223372036854775807}").getAsJsonObject());
        ConfirmationStrengthClassifier c = new ConfirmationStrengthClassifier(large);
        assertEquals(ConfirmationStrength.BELOW_NORMAL, c.classify(Long.MAX_VALUE - 1));
        assertEquals(ConfirmationStrength.NORMAL, c.classify(Long.MAX_VALUE));
    }
    @Test void tiesAreStableAndPreferSizeThenTimeThenId() {
        PatternEvent bid = event(PatternEventType.BIDS_CANCELLED, 3000, 5105, 100, "bid");
        ConfirmationMatch a = new ConfirmationMatch(bid, event(PatternEventType.OFFER_BOUNCE, 60000, 5120, 99, "a"));
        ConfirmationMatch b = new ConfirmationMatch(bid, event(PatternEventType.OFFER_BOUNCE, 60000, 5120, 98, "b"));
        ConfirmationMatch z = new ConfirmationMatch(bid, event(PatternEventType.OFFER_BOUNCE, 60000, 5120, 99, "z"));
        assertSame(a, classifier.select(List.of(z, b, a)).strongest);
        assertSame(a, classifier.select(List.of(a, b, z)).strongest);
        assertEquals(ConfirmationStrength.NONE, classifier.select(Collections.emptyList()).strength);
    }
    @Test void contextualPolicyKeepsHardMinimumAndInclusiveAcceptance() {
        TriggerRequirementPolicy policy = new TriggerRequirementPolicy(config);
        for (ConfirmationStrength band : ConfirmationStrength.values()) {
            long required = policy.requiredSize(band);
            assertTrue(policy.accepts(required, band)); assertFalse(policy.accepts(required - 1, band));
        }
        assertEquals(5000, policy.requiredSize(ConfirmationStrength.STRONG));
        assertEquals(4000, policy.requiredSize(ConfirmationStrength.VERY_STRONG));
        assertEquals(3000, policy.requiredSize(ConfirmationStrength.EXCEPTIONAL));
        assertFalse(policy.accepts(2999, ConfirmationStrength.EXCEPTIONAL));
    }
}
