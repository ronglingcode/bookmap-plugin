package com.bookmap.plugin.rong.patterns;

/** Observation semantics, deliberately independent of legacy tradebook routing. */
public enum PatternEventType {
    BID_REAPPEAR(PatternSide.BID, PatternMeaning.BID_HOLD),
    BID_STEP_UP(PatternSide.BID, PatternMeaning.BID_HOLD),
    OFFER_REAPPEAR(PatternSide.OFFER, PatternMeaning.OFFER_BEARISH_CONFIRMATION),
    OFFER_STEP_DOWN(PatternSide.OFFER, PatternMeaning.OFFER_BEARISH_CONFIRMATION),
    BIDS_CANCELLED(PatternSide.BID, PatternMeaning.BID_FAIL),
    BID_BREAKDOWN(PatternSide.BID, PatternMeaning.BID_FAIL),
    OFFER_REJECTION(PatternSide.OFFER, PatternMeaning.OFFER_BEARISH_CONFIRMATION),
    OFFER_SIZE_INCREASING_REJECTION(PatternSide.OFFER, PatternMeaning.OFFER_BEARISH_CONFIRMATION),
    OFFER_BREAKOUT(PatternSide.OFFER, PatternMeaning.OFFER_BULLISH_CONFIRMATION),
    OFFER_SIZE_INCREASE(PatternSide.OFFER, PatternMeaning.UNKNOWN),
    UNKNOWN_BID_LOSS(PatternSide.BID, PatternMeaning.UNKNOWN),
    UNKNOWN_OFFER_LOSS(PatternSide.OFFER, PatternMeaning.UNKNOWN);

    public final PatternSide side;
    public final PatternMeaning meaning;

    PatternEventType(PatternSide side, PatternMeaning meaning) {
        this.side = side;
        this.meaning = meaning;
    }
}
