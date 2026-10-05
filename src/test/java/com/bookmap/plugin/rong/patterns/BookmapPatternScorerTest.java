package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BookmapPatternScorerTest {

    private final BookmapPatternScorer scorer = new BookmapPatternScorer();

    @Test
    void sessionExtremeDetailsBelongToStepPatterns() {
        PatternDefinition[] definitions = {
                new ReappearPatternDefinition(PatternType.OFFER_REAPPEAR, false),
                new ReappearPatternDefinition(PatternType.BID_REAPPEAR, true),
                new StepPatternDefinition(PatternType.OFFER_STEP_DOWN, false),
                new StepPatternDefinition(PatternType.BID_STEP_UP, true)
        };
        assertFalse(definitions[0].requiredDetails().contains(PatternDetailKey.SESSION_EXTREMES));
        assertFalse(definitions[1].requiredDetails().contains(PatternDetailKey.SESSION_EXTREMES));
        assertTrue(definitions[2].requiredDetails().contains(PatternDetailKey.SESSION_EXTREMES));
        assertTrue(definitions[3].requiredDetails().contains(PatternDetailKey.SESSION_EXTREMES));
    }

    @Test
    void appliesMirroredNearbyWallPenaltyAndClamp() {
        PatternCandidate shortCandidate = candidate(PatternType.OFFER_REAPPEAR);
        BookmapPatternScorer.ScoreResult result = scorer.score(
                shortCandidate, new Context() {
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
                0, 500, 10_000, 200, 120, 80);
        PatternCandidate.Builder builder = PatternCandidate.builder(type, type.name(), wall)
                .triggerPriceTick(100)
                .confirmation("test")
                .replacementSizeRatio(1.0)
                .defendedMs(1_000)
                .event(10_000_000_000L, 10_000);
        return builder.build();
    }

    private static PatternDefinition definition(PatternType type) {
        switch (type.getFamily()) {
            case REAPPEAR:
                return new ReappearPatternDefinition(type, type.isBidWallPattern());
            case STEP:
                return new StepPatternDefinition(type, type.isBidWallPattern());
            default:
                throw new IllegalArgumentException(type.name());
        }
    }

    private static class Context implements PatternScoringContext {
        @Override public int currentPriceTick() { return 100; }
        @Override public int nearDistanceTicks(int priceTick) { return 10; }
        @Override public int largestOpposingWallSize(
                Direction direction, int triggerPriceTick, int nearDistanceTicks) { return 0; }
        @Override public boolean alignsWithConfiguredLevel(int priceTick, int nearDistanceTicks) { return false; }
    }
}
