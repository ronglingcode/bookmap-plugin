package com.bookmap.plugin.rong.patterns;

/** Starts directly with bid tracking, independently of any offer breakout. */
public final class FalseBreakdownMonitor extends WallPatternMonitor {
    public static final String PATTERN = "falseBreakdown";
    public static final int MAX_ENTRIES = 3;
    public FalseBreakdownMonitor() { super(Phase.BID_BREAKDOWN, MAX_ENTRIES); }
}
