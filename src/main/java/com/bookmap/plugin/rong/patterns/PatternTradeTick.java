package com.bookmap.plugin.rong.patterns;

final class PatternTradeTick {
    final int priceTick;
    final int size;
    final boolean bidAggressor;
    final long eventTimeNs;
    final long eventTimeMs;
    final int priorSessionHigh;
    final int priorSessionLow;

    PatternTradeTick(int priceTick, int size, boolean bidAggressor, long eventTimeNs, long eventTimeMs,
                     int priorSessionHigh, int priorSessionLow) {
        this.priceTick = priceTick;
        this.size = size;
        this.bidAggressor = bidAggressor;
        this.eventTimeNs = eventTimeNs;
        this.eventTimeMs = eventTimeMs;
        this.priorSessionHigh = priorSessionHigh;
        this.priorSessionLow = priorSessionLow;
    }
}
