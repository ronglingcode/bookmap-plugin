package com.bookmap.plugin.activetrader;

public class BreakoutSignal {

    public final String symbol;
    public final double breakoutLevel;
    public final double swingLow;
    public final long timestamp;

    public BreakoutSignal(String symbol, double breakoutLevel, double swingLow) {
        this.symbol = symbol;
        this.breakoutLevel = breakoutLevel;
        this.swingLow = swingLow;
        this.timestamp = System.currentTimeMillis();
    }

    public String toJson() {
        return String.format(
            "{\"type\":\"breakout\",\"symbol\":\"%s\",\"breakoutLevel\":%.6f,\"swingLow\":%.6f,\"timestamp\":%d}",
            symbol, breakoutLevel, swingLow, timestamp);
    }
}
