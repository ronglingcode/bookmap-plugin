package com.bookmap.plugin.rong.patterns;

interface PatternScoringContext {
    int currentPriceTick();
    double vwapTick();
    int nearDistanceTicks(int priceTick);
    int largestOpposingWallSize(Direction direction, int triggerPriceTick, int nearDistanceTicks);
    boolean alignsWithConfiguredLevel(int priceTick, int nearDistanceTicks);
}
