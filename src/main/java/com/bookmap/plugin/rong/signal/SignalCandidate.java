package com.bookmap.plugin.rong.signal;

import java.util.ArrayList;
import java.util.List;
import com.bookmap.plugin.rong.patterns.Direction;
import com.bookmap.plugin.rong.patterns.PatternEvent;

/** Mutable only inside the composer's serialized state; never published to the UI. */
final class SignalCandidate {
    final String key;
    final PatternEvent trigger;
    final Direction direction;
    final long expiresAtNs;
    final List<PatternEvent> supportingBids = new ArrayList<>();
    SignalState state = SignalState.CANDIDATE;
    TradingSignal signal;

    SignalCandidate(PatternEvent trigger, long afterWindowMs) {
        if (afterWindowMs <= 0) throw new IllegalArgumentException("Positive candidate window required");
        this.trigger = trigger; direction = TradingSignal.directionFrom(trigger);
        key = trigger.instrumentAlias + ":" + trigger.epoch + ":" + direction + ":" + trigger.interactionId;
        expiresAtNs = Math.addExact(trigger.eventTimeNs, Math.multiplyExact(afterWindowMs, 1_000_000L));
        supportingBids.add(trigger);
    }
    boolean addBidEvidence(PatternEvent event) {
        for (int i = 0; i < supportingBids.size(); i++) {
            PatternEvent previous = supportingBids.get(i);
            if (previous.id.equals(event.id)) {
                if (event.revision <= previous.revision) return false;
                supportingBids.set(i, event); return true;
            }
        }
        supportingBids.add(event); return true;
    }
}
