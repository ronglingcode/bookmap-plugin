package com.bookmap.plugin.rong.patterns;

public final class FalseBreakdownPainter extends WallPatternPainter {
    public static final String NAME_PREFIX = "falseBreakdown_";
    public FalseBreakdownPainter(FalseBreakdownMonitor monitor) {
        super(monitor, "False breakdown", FalseBreakdownMonitor.MAX_ENTRIES, 120, true);
    }
}
