package com.bookmap.plugin.rong.patterns;

import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class VShapePatternDefinition extends AbstractDirectionalPatternDefinition {

    private static final long EPISODE_TTL_MS = 5 * 60_000L;
    private final Map<String, Episode> episodes = new LinkedHashMap<>();

    VShapePatternDefinition(PatternType patternType, boolean bidWall) {
        super(patternType, bidWall);
    }

    @Override
    public Set<PatternDetailKey> requiredDetails() {
        return EnumSet.of(
                PatternDetailKey.WALL_QUALITY,
                PatternDetailKey.NEARBY_OPPOSING_LIQUIDITY,
                PatternDetailKey.CONFIGURED_LEVEL_ALIGNMENT,
                PatternDetailKey.SESSION_EXTREMES,
                PatternDetailKey.REVERSAL_TIMING);
    }

    @Override
    public void score(PatternCandidate candidate, PatternScoringContext context,
                      List<ScoreContribution> contributions) {
        if (candidate.reversalDelayMs <= 2_000) {
            contributions.add(new ScoreContribution(
                    "vshape.reverse_2s", 10, "reversed through cleared wall within 2s"));
        } else if (candidate.reversalDelayMs <= 5_000) {
            contributions.add(new ScoreContribution(
                    "vshape.reverse_5s", 5, "reversed through cleared wall within 5s"));
        }
        if (candidate.extremeBreakDelayMs <= 15_000) {
            contributions.add(new ScoreContribution(
                    "vshape.extreme_15s", 15, "recorded opposite session extreme broke within 15s"));
        } else if (candidate.extremeBreakDelayMs <= 30_000) {
            contributions.add(new ScoreContribution(
                    "vshape.extreme_30s", 10, "recorded opposite session extreme broke within 30s"));
        }
        if (candidate.referenceWall.tradedAtClear >= candidate.referenceWall.peakSize) {
            contributions.add(new ScoreContribution(
                    "vshape.wall_volume", 10, "aggressor volume reached peak wall size"));
        }
    }

    @Override
    public void reset() {
        episodes.clear();
    }

    @Override
    public void onWallCleared(WallSnapshot wall, PatternRuntimeContext context) {
        if (!matches(wall)) return;
        int oppositeExtreme = bidWall ? wall.sessionHighAtClear : wall.sessionLowAtClear;
        if (oppositeExtreme <= 0) return;
        episodes.put(wall.phaseId, new Episode(wall, oppositeExtreme));
    }

    @Override
    public void onTrade(PatternTradeTick trade, PatternRuntimeContext context) {
        cleanup(context.nowMs());
        for (Episode episode : episodes.values()) {
            if (episode.emitted) continue;
            boolean reversedThroughWall = bidWall
                    ? trade.priceTick > episode.wall.priceTick
                    : trade.priceTick < episode.wall.priceTick;
            if (reversedThroughWall && episode.reversalTimeMs == 0) {
                episode.reversalTimeMs = trade.eventTimeMs;
            }
            boolean brokeExtreme = bidWall
                    ? trade.priceTick > episode.oppositeExtreme
                    : trade.priceTick < episode.oppositeExtreme;
            if (episode.reversalTimeMs > 0 && brokeExtreme) {
                context.emit(PatternCandidate.builder(
                                patternType,
                                patternType.name() + ":" + episode.wall.phaseId,
                                episode.wall)
                        .triggerPriceTick(trade.priceTick)
                        .confirmation("opposite_session_extreme_break")
                        .reversalDelayMs(episode.reversalTimeMs - episode.wall.clearedAtMs)
                        .extremeBreakDelayMs(trade.eventTimeMs - episode.wall.clearedAtMs)
                        .event(trade.eventTimeNs, trade.eventTimeMs)
                        .build());
                episode.emitted = true;
            }
        }
    }

    @Override
    public void onTime(PatternRuntimeContext context) {
        cleanup(context.nowMs());
    }

    private void cleanup(long nowMs) {
        Iterator<Episode> iterator = episodes.values().iterator();
        while (iterator.hasNext()) {
            if (nowMs - iterator.next().wall.clearedAtMs > EPISODE_TTL_MS) iterator.remove();
        }
    }

    private static final class Episode {
        final WallSnapshot wall;
        final int oppositeExtreme;
        long reversalTimeMs;
        boolean emitted;

        Episode(WallSnapshot wall, int oppositeExtreme) {
            this.wall = wall;
            this.oppositeExtreme = oppositeExtreme;
        }
    }
}
