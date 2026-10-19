package com.bookmap.plugin.rong.patterns;

/** Uses the same next-bid, entry, and reclaim rendering as Bid Breakdown. */
public final class FalseBreakoutPainter extends BidBreakdownPainter {
    public static final String NAME_PREFIX = "falseBreakout_";
    public FalseBreakoutPainter(FalseBreakoutMonitor monitor) { super(monitor, "False breakout", 90); }
}
