package com.bookmap.plugin.rong.signal;

import java.util.Objects;
import com.bookmap.plugin.rong.patterns.PatternEvent;

public final class ConfirmationMatch {
    public enum Ordering { BEFORE, SIMULTANEOUS, AFTER }
    public final PatternEvent event;
    public final long timeDeltaNs, timeDeltaMs, priceDistanceTicks;
    public final Ordering ordering;

    public ConfirmationMatch(PatternEvent trigger, PatternEvent confirmation) {
        event = Objects.requireNonNull(confirmation);
        Objects.requireNonNull(trigger);
        if (!trigger.instrumentAlias.equals(event.instrumentAlias) || trigger.epoch != event.epoch) {
            throw new IllegalArgumentException("Confirmation belongs to another observation context");
        }
        timeDeltaNs = event.eventTimeNs - trigger.eventTimeNs;
        timeDeltaMs = timeDeltaNs / 1_000_000L;
        ordering = timeDeltaNs < 0 ? Ordering.BEFORE : timeDeltaNs > 0 ? Ordering.AFTER : Ordering.SIMULTANEOUS;
        priceDistanceTicks = Math.abs((long)event.priceTick - trigger.priceTick);
    }
}
