package com.bookmap.plugin.rong.patterns;

interface PatternRuntimeContext {
    long nowMs();
    long nowNs();
    int bestBidTick();
    int bestAskTick();
    int lastTradeTick();
    int sessionHighTick();
    int sessionLowTick();
    void emit(PatternCandidate candidate);
}
