package com.bookmap.plugin.rong.patterns;

/** Tracks an offer breakout followed by up to five distinct bid breakdowns. */
public final class FalseBreakoutMonitor extends WallPatternMonitor {
    public static final String PATTERN = "falseBreakout";
    public static final int MAX_ENTRIES = 5;
    public FalseBreakoutMonitor() { super(Phase.BREAKOUT, MAX_ENTRIES); }
    public FalseBreakoutMonitor(java.util.function.IntSupplier threshold) { super(Phase.BREAKOUT, MAX_ENTRIES, threshold); }
}
