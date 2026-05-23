package com.bookmap.plugin.rong;

public class BreakoutSignal {

    public final String symbol;
    public final double breakoutLevel;
    public final long timestamp;

    public BreakoutSignal(String symbol, double breakoutLevel) {
        this.symbol = symbol;
        this.breakoutLevel = breakoutLevel;
        this.timestamp = System.currentTimeMillis();
    }

    public String toJson() {
        return String.format(
            "{\"type\":\"breakout\",\"symbol\":\"%s\",\"breakoutLevel\":%.6f,\"timestamp\":%d}",
            symbol, breakoutLevel, timestamp);
    }
}
