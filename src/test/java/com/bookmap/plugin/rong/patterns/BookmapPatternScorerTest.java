package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class BookmapPatternScorerTest {

    private final BookmapPatternScorer scorer = new BookmapPatternScorer();

    @Test
    void vwapAffectsOnlyBreakPatterns() {
        Map<PatternType, Integer> withLowVwap = new EnumMap<>(PatternType.class);
        Map<PatternType, Integer> withHighVwap = new EnumMap<>(PatternType.class);
        for (PatternType type : PatternType.values()) {
            PatternCandidate candidate = candidate(type);
            withLowVwap.put(type, scorer.score(candidate, new Context(90), definition(type)).score);
            withHighVwap.put(type, scorer.score(candidate, new Context(110), definition(type)).score);
        }

        assertEquals(15, withLowVwap.get(PatternType.OFFER_WALL_BREAKOUT)
                - withHighVwap.get(PatternType.OFFER_WALL_BREAKOUT));
        assertEquals(-15, withLowVwap.get(PatternType.BID_WALL_BREAKDOWN)
                - withHighVwap.get(PatternType.BID_WALL_BREAKDOWN));
        for (PatternType type : PatternType.values()) {
            if (type.getFamily() != PatternType.Family.BREAK) {
                assertEquals(withLowVwap.get(type), withHighVwap.get(type), type.name());
            }
        }
    }

    @Test
    void onlyBreakDefinitionsDeclareVwapDetail() {
        PatternDefinition[] definitions = {
                new WallBreakPatternDefinition(PatternType.OFFER_WALL_BREAKOUT, false),
                new WallBreakPatternDefinition(PatternType.BID_WALL_BREAKDOWN, true),
                new ReappearPatternDefinition(PatternType.OFFER_REAPPEAR, false),
                new ReappearPatternDefinition(PatternType.BID_REAPPEAR, true),
                new StepPatternDefinition(PatternType.OFFER_STEP_DOWN, false),
                new StepPatternDefinition(PatternType.BID_STEP_UP, true),
                new VShapePatternDefinition(PatternType.OFFER_V_SHAPE_REJECTION, false),
                new VShapePatternDefinition(PatternType.BID_V_SHAPE_RECOVERY, true)
        };
        for (PatternDefinition definition : definitions) {
            boolean expected = definition.type().getFamily() == PatternType.Family.BREAK;
            assertEquals(expected,
                    definition.requiredDetails().contains(PatternDetailKey.VWAP_ALIGNMENT),
                    definition.type().name());
        }
    }

    @Test
    void appliesMirroredNearbyWallPenaltyAndClamp() {
        PatternCandidate shortCandidate = candidate(PatternType.OFFER_REAPPEAR);
        BookmapPatternScorer.ScoreResult result = scorer.score(
                shortCandidate, new Context(100) {
            @Override
            public int largestOpposingWallSize(
                    Direction direction, int triggerPriceTick, int nearDistanceTicks) {
                assertEquals(Direction.SHORT, direction);
                return 400;
            }
                }, definition(PatternType.OFFER_REAPPEAR));
        assertTrue(result.contributions.stream().anyMatch(c ->
                c.getRuleId().equals("liquidity.opposing_wall_2x") && c.getPoints() == -20));
        assertTrue(result.score >= 0 && result.score <= 100);
    }

    private static PatternCandidate candidate(PatternType type) {
        boolean bid = type.isBidWallPattern();
        WallSnapshot wall = new WallSnapshot(
                "phase", bid, 100, 100, 200, 0,
                0, 500, 10_000, 200, 100, 120, 80, false);
        PatternCandidate.Builder builder = PatternCandidate.builder(type, type.name(), wall)
                .triggerPriceTick(100)
                .confirmation(type.getFamily() == PatternType.Family.BREAK ? "hold" : "test")
                .replacementSizeRatio(1.0)
                .defendedMs(1_000)
                .reversalDelayMs(2_000)
                .extremeBreakDelayMs(15_000)
                .event(10_000_000_000L, 10_000);
        return builder.build();
    }

    private static PatternDefinition definition(PatternType type) {
        switch (type.getFamily()) {
            case BREAK:
                return new WallBreakPatternDefinition(type, type.isBidWallPattern());
            case REAPPEAR:
                return new ReappearPatternDefinition(type, type.isBidWallPattern());
            case STEP:
                return new StepPatternDefinition(type, type.isBidWallPattern());
            case V_SHAPE:
                return new VShapePatternDefinition(type, type.isBidWallPattern());
            default:
                throw new IllegalArgumentException(type.name());
        }
    }

    private static class Context implements PatternScoringContext {
        private final double vwap;

        Context(double vwap) {
            this.vwap = vwap;
        }

        @Override public int currentPriceTick() { return 100; }
        @Override public double vwapTick() { return vwap; }
        @Override public int nearDistanceTicks(int priceTick) { return 10; }
        @Override public int largestOpposingWallSize(
                Direction direction, int triggerPriceTick, int nearDistanceTicks) { return 0; }
        @Override public boolean alignsWithConfiguredLevel(int priceTick, int nearDistanceTicks) { return false; }
    }
}
