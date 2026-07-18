package com.bookmap.plugin.rong.patterns;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class StepPatternDefinition extends AbstractDirectionalPatternDefinition {

    private static final long REFERENCE_WINDOW_MS = 5 * 60_000L;
    private final List<WallSnapshot> references = new ArrayList<>();
    private final Map<String, StepEpisode> pendingUpdates = new LinkedHashMap<>();

    StepPatternDefinition(PatternType patternType, boolean bidWall) {
        super(patternType, bidWall);
    }

    @Override
    public Set<PatternDetailKey> requiredDetails() {
        return EnumSet.of(
                PatternDetailKey.WALL_QUALITY,
                PatternDetailKey.NEARBY_OPPOSING_LIQUIDITY,
                PatternDetailKey.CONFIGURED_LEVEL_ALIGNMENT,
                PatternDetailKey.COMPARABLE_REFERENCE_WALL);
    }

    @Override
    public void score(PatternCandidate candidate, PatternScoringContext context,
                      List<ScoreContribution> contributions) {
        int nearTicks = context.nearDistanceTicks(candidate.referenceWall.priceTick);
        if (Math.abs(candidate.triggerPriceTick - context.currentPriceTick()) <= nearTicks) {
            contributions.add(new ScoreContribution(
                    "step.near_quote", 10, "new wall is near the current quote"));
        }
        if (candidate.replacementSizeRatio >= 1.0) {
            contributions.add(new ScoreContribution(
                    "step.reference_size", 10, "new wall is at least as large as reference"));
        }
        if (candidate.defendedMs >= 1_000) {
            contributions.add(new ScoreContribution(
                    "step.defended_1s", 5, "price stayed on defended side for 1s"));
        }
    }

    @Override
    public void reset() {
        references.clear();
        pendingUpdates.clear();
    }

    @Override
    public void onWallCleared(WallSnapshot wall, PatternRuntimeContext context) {
        if (matches(wall)) references.add(wall);
    }

    @Override
    public void onWallQualified(WallSnapshot wall, PatternRuntimeContext context) {
        if (!matches(wall)) return;
        cleanup(context.nowMs());
        WallSnapshot reference = references.stream()
                .filter(candidate -> !candidate.phaseId.equals(wall.phaseId))
                .filter(candidate -> isImprovedPrice(wall.priceTick, candidate.priceTick))
                .filter(candidate -> wall.peakSize >= candidate.peakSize * 0.50)
                .max(Comparator.comparingInt(candidate -> candidate.peakSize))
                .orElse(null);
        references.add(wall);
        if (reference == null || !isDefended(context, wall.priceTick)) return;
        StepEpisode episode = new StepEpisode(wall, reference);
        pendingUpdates.put(wall.phaseId, episode);
        emit(episode, context);
    }

    @Override
    public void onBbo(PatternRuntimeContext context) {
        updatePending(context);
    }

    @Override
    public void onTime(PatternRuntimeContext context) {
        updatePending(context);
    }

    private void updatePending(PatternRuntimeContext context) {
        cleanup(context.nowMs());
        for (StepEpisode episode : pendingUpdates.values()) {
            if (!episode.oneSecondUpdateEmitted
                    && context.nowMs() - episode.wall.firstSeenMs >= 1_000
                    && isDefended(context, episode.wall.priceTick)) {
                emit(episode, context);
                episode.oneSecondUpdateEmitted = true;
            }
        }
    }

    private void emit(StepEpisode episode, PatternRuntimeContext context) {
        double ratio = episode.reference.peakSize == 0
                ? 0 : (double) episode.wall.peakSize / episode.reference.peakSize;
        context.emit(PatternCandidate.builder(
                        patternType,
                        patternType.name() + ":" + episode.wall.phaseId + ":" + episode.reference.phaseId,
                        episode.reference)
                .triggerWall(episode.wall)
                .triggerPriceTick(episode.wall.priceTick)
                .confirmation("step")
                .replacementSizeRatio(ratio)
                .betterDefensivePrice(true)
                .defendedMs(episode.wall.durationMs(context.nowMs()))
                .event(context.nowNs(), context.nowMs())
                .build());
    }

    private boolean isImprovedPrice(int newPrice, int oldPrice) {
        return bidWall ? newPrice > oldPrice : newPrice < oldPrice;
    }

    private void cleanup(long nowMs) {
        Iterator<WallSnapshot> iterator = references.iterator();
        while (iterator.hasNext()) {
            WallSnapshot reference = iterator.next();
            long anchor = reference.clearedAtMs > 0 ? reference.clearedAtMs : reference.qualifiedAtMs;
            if (nowMs - anchor > REFERENCE_WINDOW_MS) iterator.remove();
        }
        pendingUpdates.entrySet().removeIf(entry ->
                nowMs - entry.getValue().wall.qualifiedAtMs > REFERENCE_WINDOW_MS);
    }

    private static final class StepEpisode {
        final WallSnapshot wall;
        final WallSnapshot reference;
        boolean oneSecondUpdateEmitted;

        StepEpisode(WallSnapshot wall, WallSnapshot reference) {
            this.wall = wall;
            this.reference = reference;
        }
    }
}
