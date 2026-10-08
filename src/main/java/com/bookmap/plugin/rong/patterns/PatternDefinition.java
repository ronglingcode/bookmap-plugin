package com.bookmap.plugin.rong.patterns;


interface PatternDefinition {
    PatternType type();
    void reset();
    default void onWallCleared(WallSnapshot wall, PatternRuntimeContext context) { }
    default void onWallQualified(WallSnapshot wall, PatternRuntimeContext context) { }
    default void onTrade(PatternTradeTick trade, PatternRuntimeContext context) { }
    default void onBbo(PatternRuntimeContext context) { }
    default void onTime(PatternRuntimeContext context) { }
}
