package com.bookmap.plugin.rong.patterns;

public enum PatternMeaning {
    BID_HOLD, BID_FAIL, OFFER_BEARISH_CONFIRMATION, OFFER_BULLISH_CONFIRMATION, UNKNOWN;

    public boolean isCompatible(PatternSide side) {
        if (side == null) return false;
        return this == UNKNOWN || ((this == BID_HOLD || this == BID_FAIL) == (side == PatternSide.BID));
    }
}
