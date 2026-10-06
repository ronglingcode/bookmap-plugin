package com.bookmap.plugin.rong.signal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import com.bookmap.plugin.rong.patterns.Direction;
import com.bookmap.plugin.rong.patterns.PatternEvent;
import com.bookmap.plugin.rong.patterns.PatternMeaning;

/** Immutable advisory result. Subsequent evidence never changes its first acceptance. */
public final class TradingSignal {
    public final String id, configRevision, explanation;
    public final int revision;
    public final Direction direction;
    public final PatternEvent trigger;
    public final FirstValidation firstValidation;
    public final List<ConfirmationMatch> subsequentConfirmations, confirmations;
    public final List<PatternEvent> supportingBidEvidence;
    public final ConfirmationStrength latestConfirmationStrength;
    public final long createdAtMs;

    public TradingSignal(PatternEvent trigger, FirstValidation validation, List<ConfirmationMatch> subsequent,
            List<PatternEvent> supportingBids, ConfirmationStrength latestStrength,
            int revision, long createdAtMs, String configRevision, String explanation) {
        this.trigger = Objects.requireNonNull(trigger);
        direction = directionFrom(trigger);
        firstValidation = Objects.requireNonNull(validation);
        if (revision < 1 || createdAtMs < 0 || validation.eventTimeNs < trigger.observedAtNs
                || trigger.size < validation.appliedTriggerThreshold) throw new IllegalArgumentException("Invalid signal validation");
        for (ConfirmationMatch match : validation.confirmations) {
            if (match.event.observedAtNs > validation.eventTimeNs) throw new IllegalArgumentException("Validation cannot use future evidence");
        }
        this.revision = revision; this.createdAtMs = createdAtMs;
        this.configRevision = Objects.requireNonNull(configRevision); this.explanation = Objects.requireNonNull(explanation);
        latestConfirmationStrength = Objects.requireNonNull(latestStrength);
        subsequentConfirmations = immutable(subsequent);
        supportingBidEvidence = immutable(supportingBids);
        List<ConfirmationMatch> all = new ArrayList<>(validation.confirmations); all.addAll(subsequentConfirmations);
        confirmations = Collections.unmodifiableList(all);
        id = trigger.instrumentAlias + ":" + trigger.epoch + ":signal:" + direction + ":" + trigger.interactionId;
    }
    static <T> List<T> immutable(List<T> source) {
        List<T> copy = new ArrayList<>(Objects.requireNonNull(source));
        copy.forEach(Objects::requireNonNull);
        return Collections.unmodifiableList(copy);
    }
    public static Direction directionFrom(PatternEvent event) {
        if (event.meaning == PatternMeaning.BID_HOLD) return Direction.LONG;
        if (event.meaning == PatternMeaning.BID_FAIL) return Direction.SHORT;
        throw new IllegalArgumentException("A composed signal requires bid behavior");
    }
    public static final class FirstValidation {
        public final long eventTimeNs, normalTriggerThreshold, appliedTriggerThreshold;
        public final ConfirmationStrength confirmationStrength;
        public final List<ConfirmationMatch> confirmations;
        public final ConfirmationMatch strongestConfirmation;
        public FirstValidation(long timeNs, long normalSize, long appliedSize, ConfirmationStrength strength,
                List<ConfirmationMatch> confirmations, ConfirmationMatch strongest) {
            if (timeNs <= 0 || normalSize <= 0 || appliedSize <= 0 || appliedSize > normalSize) {
                throw new IllegalArgumentException("Invalid validation time or thresholds");
            }
            eventTimeNs = timeNs; normalTriggerThreshold = normalSize; appliedTriggerThreshold = appliedSize;
            confirmationStrength = Objects.requireNonNull(strength);
            this.confirmations = immutable(confirmations); strongestConfirmation = strongest;
            if ((strongest == null) != (strength == ConfirmationStrength.NONE)
                    || (strongest != null && !this.confirmations.contains(strongest))) {
                throw new IllegalArgumentException("Strongest evidence must match the validation snapshot");
            }
        }
    }
}
