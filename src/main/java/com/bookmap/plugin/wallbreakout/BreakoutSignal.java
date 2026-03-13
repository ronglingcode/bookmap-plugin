package com.bookmap.plugin.wallbreakout;

public class BreakoutSignal {

    public final double breakoutLevel;
    public final double swingLow;
    public final long timestamp;

    public BreakoutSignal(double breakoutLevel, double swingLow) {
        this.breakoutLevel = breakoutLevel;
        this.swingLow = swingLow;
        this.timestamp = System.currentTimeMillis();
    }

    public String toJson() {
        return String.format(
            "{\"type\":\"breakout\",\"breakoutLevel\":%.6f,\"swingLow\":%.6f,\"timestamp\":%d}",
            breakoutLevel, swingLow, timestamp);
    }
}
