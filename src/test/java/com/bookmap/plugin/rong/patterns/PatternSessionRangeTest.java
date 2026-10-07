package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatternSessionRangeTest {
    @Test
    void allFourPatternsRequireTriggerStrictlyInsideBothKnownExtremes() {
        int[][] ranges = {{100, 200}, {0, 200}, {100, 0}, {0, 0}, {150, 150}, {200, 100}};
        for (PatternType type : PatternType.values()) {
            for (int[] range : ranges) {
                for (int price : new int[] {99, 100, 101, 150, 199, 200, 201}) {
                    Context context = new Context(range[0], range[1]);
                    PatternDefinition definition = definition(type);
                    boolean bid = type.isBidWallPattern();
                    WallSnapshot reference = wall("reference", bid, price + (bid ? -1 : 1));
                    // Cleared references work for both families, including out-of-range references.
                    definition.onWallCleared(reference, context);
                    definition.onWallQualified(wall("replacement", bid, price), context);
                    boolean expected = range[0] > 0 && range[1] > range[0]
                            && price > range[0] && price < range[1];
                    assertEquals(expected ? 1 : 0, context.emitted.size(),
                            type + " price=" + price + " low=" + range[0] + " high=" + range[1]);
                }
            }
        }
    }

    @Test
    void delayedStepUpdatesAlsoRequireCurrentRange() {
        for (PatternType type : new PatternType[] {PatternType.BID_STEP_UP, PatternType.OFFER_STEP_DOWN}) {
            Context context = new Context(100, 200);
            PatternDefinition definition = definition(type);
            boolean bid = type.isBidWallPattern();
            definition.onWallQualified(wall("reference", bid, bid ? 140 : 160), context);
            definition.onWallQualified(wall("replacement", bid, 150), context);
            assertEquals(1, context.emitted.size());
            context.low = 0;
            context.now = 1500;
            definition.onTime(context);
            definition.onBbo(context);
            assertEquals(1, context.emitted.size());
            context.low = 100;
            definition.onTime(context);
            assertEquals(2, context.emitted.size());
        }
    }

    private static PatternDefinition definition(PatternType type) {
        return type.getFamily() == PatternType.Family.REAPPEAR
                ? new ReappearPatternDefinition(type, type.isBidWallPattern())
                : new StepPatternDefinition(type, type.isBidWallPattern());
    }

    private static WallSnapshot wall(String id, boolean bid, int price) {
        return new WallSnapshot(id, bid, price, 3000, 3000, 3000, 0, 500, 500, 1000, 0, 0);
    }

    private static final class Context implements PatternRuntimeContext {
        int low;
        final int high;
        long now = 500;
        final List<PatternCandidate> emitted = new ArrayList<>();
        Context(int low, int high) { this.low = low; this.high = high; }
        public long nowMs() { return now; }
        public long nowNs() { return now * 1_000_000L; }
        public int bestBidTick() { return 1; }
        public int bestAskTick() { return 1000; }
        public int lastTradeTick() { return 150; }
        public int sessionHighTick() { return high; }
        public int sessionLowTick() { return low; }
        public void emit(PatternCandidate candidate) { emitted.add(candidate); }
    }
}
