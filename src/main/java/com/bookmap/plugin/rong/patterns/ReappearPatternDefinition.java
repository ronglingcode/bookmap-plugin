package com.bookmap.plugin.rong.patterns;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

final class ReappearPatternDefinition extends AbstractDirectionalPatternDefinition {

    private static final long REAPPEAR_WINDOW_MS = 5 * 60_000L;
    private final List<WallSnapshot> clearedWalls = new ArrayList<>();

    ReappearPatternDefinition(PatternType patternType, boolean bidWall) {
        super(patternType, bidWall);
    }

    @Override
    public void reset() {
        clearedWalls.clear();
    }

    @Override
    public void onWallCleared(WallSnapshot wall, PatternRuntimeContext context) {
        if (matches(wall)) clearedWalls.add(wall);
    }

    @Override
    public void onWallQualified(WallSnapshot wall, PatternRuntimeContext context) {
        if (!matches(wall) || !isWithinSessionRange(context, wall.priceTick)
                || !isDefended(context, wall.priceTick)) return;
        cleanup(context.nowMs());
        WallSnapshot reference = clearedWalls.stream()
                .filter(candidate -> !candidate.phaseId.equals(wall.phaseId))
                .filter(candidate -> isSameOrBetterPrice(wall.priceTick, candidate.priceTick))
                .filter(candidate -> wall.peakSize >= candidate.peakSize * 0.50)
                .max(Comparator.comparingLong(candidate -> candidate.clearedAtMs))
                .orElse(null);
        if (reference == null) return;
        double ratio = reference.peakSize == 0 ? 0 : (double) wall.peakSize / reference.peakSize;
        boolean better = bidWall ? wall.priceTick > reference.priceTick : wall.priceTick < reference.priceTick;
        context.emit(PatternCandidate.builder(
                        patternType,
                        patternType.name() + ":" + wall.phaseId + ":" + reference.phaseId,
                        reference)
                .triggerWall(wall)
                .triggerPriceTick(wall.priceTick)
                .confirmation("reappear")
                .replacementSizeRatio(ratio)
                .betterDefensivePrice(better)
                .defendedMs(wall.durationMs(context.nowMs()))
                .event(context.nowNs(), context.nowMs())
                .build());
    }

    private boolean isSameOrBetterPrice(int newPrice, int oldPrice) {
        return bidWall ? newPrice >= oldPrice : newPrice <= oldPrice;
    }

    private void cleanup(long nowMs) {
        Iterator<WallSnapshot> iterator = clearedWalls.iterator();
        while (iterator.hasNext()) {
            if (nowMs - iterator.next().clearedAtMs > REAPPEAR_WINDOW_MS) iterator.remove();
        }
    }
}
