package com.bookmap.plugin.rong.signal;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.bookmap.plugin.rong.patterns.Direction;

/** Complete immutable context snapshot plus only the signals/transitions changed by this callback. */
public final class CompositionUpdate {
    public final List<TradingSignal> signals;
    public final Map<Direction, DevelopingContext> contexts;
    public final List<CandidateTransition> transitions;
    public final List<String> diagnostics;
    public final long marketTimeNs;
    public CompositionUpdate(List<TradingSignal> signals, Map<Direction, DevelopingContext> contexts,
            List<CandidateTransition> transitions, List<String> diagnostics, long marketTimeNs) {
        this.signals = TradingSignal.immutable(signals); this.transitions = TradingSignal.immutable(transitions);
        this.diagnostics = TradingSignal.immutable(diagnostics);
        Map<Direction, DevelopingContext> copy = new EnumMap<>(Direction.class); copy.putAll(contexts);
        copy.forEach((k, v) -> { Objects.requireNonNull(k); Objects.requireNonNull(v); });
        this.contexts = Collections.unmodifiableMap(copy); this.marketTimeNs = marketTimeNs;
    }
    public static final class CandidateTransition {
        public final String candidateId, reason;
        public final SignalState previous, current;
        public CandidateTransition(String id, SignalState previous, SignalState current, String reason) {
            candidateId = Objects.requireNonNull(id); this.previous = Objects.requireNonNull(previous);
            this.current = Objects.requireNonNull(current); this.reason = Objects.requireNonNull(reason);
        }
    }
}
