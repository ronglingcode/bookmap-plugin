package com.bookmap.plugin.rong.patterns;

abstract class AbstractDirectionalPatternDefinition implements PatternDefinition {
    final PatternType patternType;
    final boolean bidWall;

    AbstractDirectionalPatternDefinition(PatternType patternType, boolean bidWall) {
        this.patternType = patternType;
        this.bidWall = bidWall;
    }

    @Override
    public PatternType type() {
        return patternType;
    }

    boolean matches(WallSnapshot wall) {
        return wall.bid == bidWall;
    }

    boolean isDefended(PatternRuntimeContext context, int wallPriceTick) {
        if (bidWall) {
            int bestAsk = context.bestAskTick();
            return bestAsk > 0 && bestAsk > wallPriceTick;
        }
        int bestBid = context.bestBidTick();
        return bestBid > 0 && bestBid < wallPriceTick;
    }
}
