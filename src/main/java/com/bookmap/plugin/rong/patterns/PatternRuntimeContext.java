package com.bookmap.plugin.rong.patterns;

interface PatternRuntimeContext {
    long nowMs();
    long nowNs();
    int bestBidTick();
    int bestAskTick();
    int lastTradeTick();
    void emit(PatternCandidate candidate);
}
