package com.bookmap.plugin.rong.signal;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import com.bookmap.plugin.rong.PluginLog;
import com.bookmap.plugin.rong.patterns.Direction;
import com.bookmap.plugin.rong.patterns.PatternEvent;

/** Formatting/dispatch only. Production persistence uses PluginLog's existing bounded queue. */
public final class SignalCompositionLog {
    public interface Sink {
        void summary(String alias, String full, String concise);
        void detail(String alias, String full);
    }
    private final String alias;
    private final int capacity;
    private final Sink sink;
    private final Map<String, Integer> revisions = new LinkedHashMap<>();
    private final Map<Direction, String> contexts = new EnumMap<>(Direction.class);
    public SignalCompositionLog(String alias, int capacity) {
        this(alias, capacity, new Sink() {
            public void summary(String symbol, String full, String concise) { PluginLog.summary(symbol, "SignalComposer", full, concise); }
            public void detail(String symbol, String full) { PluginLog.detail(symbol, "SignalComposer", full); }
        });
    }
    public SignalCompositionLog(String alias, int capacity, Sink sink) { this.alias = alias; this.capacity = capacity; this.sink = sink; }
    public void update(CompositionUpdate update) {
        for (TradingSignal signal : update.signals) {
            int previous = revisions.getOrDefault(signal.id, 0); if (signal.revision <= previous) continue;
            revisions.put(signal.id, signal.revision);
            while (revisions.size() > capacity) revisions.remove(revisions.keySet().iterator().next());
            String full = "Signal " + signal.id + " revision=" + signal.revision + " config=" + signal.configRevision + "\n" + signal.explanation;
            if (previous == 0 && signal.revision == 1) {
                sink.summary(alias, full, "Advisory " + signal.direction + " · " + signal.trigger.type + " "
                        + TradingSignalPainter.quantity(signal.trigger.size) + " · " + signal.firstValidation.confirmationStrength
                        + " · applied " + TradingSignalPainter.quantity(signal.firstValidation.appliedTriggerThreshold)
                        + " · " + SignalExplanationBuilder.price(signal.trigger.price));
            } else sink.detail(alias, full);
        }
        for (CompositionUpdate.CandidateTransition transition : update.transitions)
            detail("Candidate " + transition.candidateId + " " + transition.previous + " -> " + transition.current + ": " + transition.reason);
        for (String diagnostic : update.diagnostics) detail(diagnostic);
        for (Direction direction : Direction.values()) {
            DevelopingContext context = update.contexts.get(direction);
            String value = context == null ? null : context.confirmation.id + ":" + context.confirmation.revision + ":" + context.requiredTriggerSize;
            if (java.util.Objects.equals(contexts.get(direction), value)) continue;
            if (value == null) { contexts.remove(direction); detail("Waiting context removed: " + direction); }
            else {
                contexts.put(direction, value); detail("Waiting context " + direction + ": " + context.confirmation.type
                        + " " + context.confirmation.size + " shares; " + context.strength + "; waiting for " + context.waitingFor
                        + " >= " + context.requiredTriggerSize + "; expiresAtNs=" + context.expiresAtNs);
            }
        }
    }
    public void event(PatternEvent event) {
        detail("Semantic event " + event.id + " revision=" + event.revision + " interaction=" + event.interactionId
                + " type=" + event.type + " meaning=" + event.meaning + " size=" + event.size + " category=" + event.sizeCategory.label + " basis=" + event.sizeBasis
                + " price=" + event.price + " occurrenceNs=" + event.eventTimeNs + " observedAtNs=" + event.observedAtNs
                + " provenance=" + event.timestampProvenance + " coverage=" + event.evidence.coverage
                + " attribution=" + event.evidence.attribution + " removed=" + event.evidence.removedSize
                + " trades=" + event.evidence.attributedTradeSize + " growthBaseline=" + event.evidence.growthBaselineSize
                + " approachNs=" + event.evidence.approachTimeNs + " rejectionNs=" + event.evidence.rejectionTimeNs);
    }
    public void detail(String message) { sink.detail(alias, message); }
}
