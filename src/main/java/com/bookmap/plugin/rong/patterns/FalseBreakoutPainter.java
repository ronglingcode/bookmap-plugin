package com.bookmap.plugin.rong.patterns;

public final class FalseBreakoutPainter extends WallPatternPainter {
    public static final String NAME_PREFIX = "falseBreakout_";
    public FalseBreakoutPainter(FalseBreakoutMonitor monitor) {
        super(monitor, "False breakout", FalseBreakoutMonitor.MAX_ENTRIES, 90, false);
    }
}
