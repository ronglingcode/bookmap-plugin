package com.bookmap.plugin.rong.signal;

import java.util.Objects;
import com.bookmap.plugin.rong.patterns.Direction;
import com.bookmap.plugin.rong.patterns.PatternEvent;
import com.bookmap.plugin.rong.patterns.PatternMeaning;

public final class DevelopingContext {
    public final Direction direction;
    public final PatternEvent confirmation;
    public final ConfirmationStrength strength;
    public final PatternMeaning waitingFor;
    public final long requiredTriggerSize, expiresAtNs;
    public DevelopingContext(Direction direction, PatternEvent evidence, ConfirmationStrength strength, long requiredSize, long expiresNs) {
        this.direction = Objects.requireNonNull(direction); confirmation = Objects.requireNonNull(evidence);
        this.strength = Objects.requireNonNull(strength);
        PatternMeaning expected = direction == Direction.LONG ? PatternMeaning.OFFER_BULLISH_CONFIRMATION : PatternMeaning.OFFER_BEARISH_CONFIRMATION;
        if (evidence.meaning != expected || requiredSize <= 0 || expiresNs < evidence.observedAtNs) throw new IllegalArgumentException("Invalid directional context");
        waitingFor = direction == Direction.LONG ? PatternMeaning.BID_HOLD : PatternMeaning.BID_FAIL;
        requiredTriggerSize = requiredSize; expiresAtNs = expiresNs;
    }
}
