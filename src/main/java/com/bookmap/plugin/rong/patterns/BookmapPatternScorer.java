package com.bookmap.plugin.rong.patterns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pattern-local quality rules. Eligibility is handled by the pattern state machines. */
final class BookmapPatternScorer {

    static final int BASE_SCORE = 40;

    ScoreResult score(PatternCandidate candidate, PatternScoringContext context,
                      PatternDefinition definition) {
        List<ScoreContribution> contributions = new ArrayList<>();
        WallSnapshot wall = candidate.triggerWall;
        int nearTicks = context.nearDistanceTicks(candidate.referenceWall.priceTick);

        addBand(contributions, "wall.threshold_ratio", ratio(wall.peakSize, wall.effectiveThreshold),
                1.5, 5, "wall is 1.5x threshold",
                2.0, 10, "wall is 2x+ threshold");
        addTimeBand(contributions, "wall.duration", wall.durationMs(candidate.eventTimeMs),
                3_000, 5, "wall persisted 3s+",
                10_000, 10, "wall persisted 10s+");
        double growth = wall.initialSize <= 0 ? 0 : ratio(wall.peakSize - wall.initialSize, wall.initialSize);
        addBand(contributions, "wall.growth", growth,
                0.25, 5, "wall grew 25%+",
                1.0, 10, "wall grew 100%+");

        if (context.alignsWithConfiguredLevel(candidate.triggerWall.priceTick, nearTicks)) {
            add(contributions, "level.alignment", 10, "aligned with configured level/zone");
        }

        int opposingSize = context.largestOpposingWallSize(
                candidate.patternType.getDirection(), candidate.referenceWall.priceTick, nearTicks);
        double opposingRatio = ratio(opposingSize, candidate.referenceWall.peakSize);
        if (opposingRatio >= 2.0) {
            add(contributions, "liquidity.opposing_wall_2x", -20, "nearby opposing wall is 2x+ reference");
        } else if (opposingRatio >= 1.5) {
            add(contributions, "liquidity.opposing_wall_1_5x", -10, "nearby opposing wall is 1.5x+ reference");
        }

        int distanceFromWall = directionalDistance(
                candidate.patternType.getDirection(), candidate.triggerPriceTick, candidate.referenceWall.priceTick);
        if (distanceFromWall > nearTicks) {
            add(contributions, "price.confirmed_too_far", -10, "confirmation is beyond nearby-liquidity range");
        }

        definition.score(candidate, context, contributions);

        int score = BASE_SCORE;
        for (ScoreContribution contribution : contributions) score += contribution.getPoints();
        return new ScoreResult(Math.max(0, Math.min(100, score)), contributions);
    }

    private static int directionalDistance(Direction direction, int trigger, int wall) {
        return direction == Direction.LONG ? trigger - wall : wall - trigger;
    }

    private static double ratio(long numerator, long denominator) {
        return denominator <= 0 ? 0 : (double) numerator / denominator;
    }

    private static void addBand(List<ScoreContribution> target, String rulePrefix, double value,
                                double lowThreshold, int lowPoints, String lowDetail,
                                double highThreshold, int highPoints, String highDetail) {
        if (value >= highThreshold) add(target, rulePrefix + ".high", highPoints, highDetail);
        else if (value >= lowThreshold) add(target, rulePrefix + ".medium", lowPoints, lowDetail);
    }

    private static void addTimeBand(List<ScoreContribution> target, String rulePrefix, long value,
                                    long lowThreshold, int lowPoints, String lowDetail,
                                    long highThreshold, int highPoints, String highDetail) {
        if (value >= highThreshold) add(target, rulePrefix + ".high", highPoints, highDetail);
        else if (value >= lowThreshold) add(target, rulePrefix + ".medium", lowPoints, lowDetail);
    }

    private static void add(List<ScoreContribution> target, String ruleId, int points, String detail) {
        target.add(new ScoreContribution(ruleId, points, detail));
    }

    static final class ScoreResult {
        final int score;
        final List<ScoreContribution> contributions;

        ScoreResult(int score, List<ScoreContribution> contributions) {
            this.score = score;
            this.contributions = Collections.unmodifiableList(new ArrayList<>(contributions));
        }
    }
}
