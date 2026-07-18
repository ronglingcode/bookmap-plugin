package com.bookmap.plugin.rong.patterns;

import java.util.Set;
import java.util.List;

interface PatternDefinition {
    PatternType type();
    Set<PatternDetailKey> requiredDetails();
    void score(PatternCandidate candidate, PatternScoringContext context,
               List<ScoreContribution> contributions);
    void reset();
    default void onWallCleared(WallSnapshot wall, PatternRuntimeContext context) { }
    default void onWallQualified(WallSnapshot wall, PatternRuntimeContext context) { }
    default void onTrade(PatternTradeTick trade, PatternRuntimeContext context) { }
    default void onBbo(PatternRuntimeContext context) { }
    default void onTime(PatternRuntimeContext context) { }
}
