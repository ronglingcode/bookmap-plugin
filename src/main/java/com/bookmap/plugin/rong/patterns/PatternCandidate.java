package com.bookmap.plugin.rong.patterns;

final class PatternCandidate {
    final PatternType patternType;
    final String episodeKey;
    final int triggerPriceTick;
    final WallSnapshot referenceWall;
    final WallSnapshot triggerWall;
    final String confirmation;
    final double replacementSizeRatio;
    final boolean betterDefensivePrice;
    final long defendedMs;
    final long reversalDelayMs;
    final long extremeBreakDelayMs;
    final long eventTimeNs;
    final long eventTimeMs;

    private PatternCandidate(Builder builder) {
        patternType = builder.patternType;
        episodeKey = builder.episodeKey;
        triggerPriceTick = builder.triggerPriceTick;
        referenceWall = builder.referenceWall;
        triggerWall = builder.triggerWall == null ? builder.referenceWall : builder.triggerWall;
        confirmation = builder.confirmation;
        replacementSizeRatio = builder.replacementSizeRatio;
        betterDefensivePrice = builder.betterDefensivePrice;
        defendedMs = builder.defendedMs;
        reversalDelayMs = builder.reversalDelayMs;
        extremeBreakDelayMs = builder.extremeBreakDelayMs;
        eventTimeNs = builder.eventTimeNs;
        eventTimeMs = builder.eventTimeMs;
    }

    static Builder builder(PatternType type, String episodeKey, WallSnapshot referenceWall) {
        return new Builder(type, episodeKey, referenceWall);
    }

    static final class Builder {
        private final PatternType patternType;
        private final String episodeKey;
        private final WallSnapshot referenceWall;
        private int triggerPriceTick;
        private WallSnapshot triggerWall;
        private String confirmation = "";
        private double replacementSizeRatio;
        private boolean betterDefensivePrice;
        private long defendedMs;
        private long reversalDelayMs = Long.MAX_VALUE;
        private long extremeBreakDelayMs = Long.MAX_VALUE;
        private long eventTimeNs;
        private long eventTimeMs;

        private Builder(PatternType patternType, String episodeKey, WallSnapshot referenceWall) {
            this.patternType = patternType;
            this.episodeKey = episodeKey;
            this.referenceWall = referenceWall;
            this.triggerPriceTick = referenceWall.priceTick;
        }

        Builder triggerPriceTick(int value) { triggerPriceTick = value; return this; }
        Builder triggerWall(WallSnapshot value) { triggerWall = value; return this; }
        Builder confirmation(String value) { confirmation = value; return this; }
        Builder replacementSizeRatio(double value) { replacementSizeRatio = value; return this; }
        Builder betterDefensivePrice(boolean value) { betterDefensivePrice = value; return this; }
        Builder defendedMs(long value) { defendedMs = value; return this; }
        Builder reversalDelayMs(long value) { reversalDelayMs = value; return this; }
        Builder extremeBreakDelayMs(long value) { extremeBreakDelayMs = value; return this; }
        Builder event(long timeNs, long timeMs) { eventTimeNs = timeNs; eventTimeMs = timeMs; return this; }
        PatternCandidate build() { return new PatternCandidate(this); }
    }
}
