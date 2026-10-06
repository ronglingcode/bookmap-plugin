package com.bookmap.plugin.rong.signal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import com.bookmap.plugin.rong.patterns.PatternEvent;
import com.bookmap.plugin.rong.patterns.PatternMeaning;

/** Serialized by the owning observation engine. All eligibility uses market time. */
public final class SignalComposer {
    private final String alias;
    private long epoch, nowNs;
    private final SignalComposerConfig config;
    private final PatternEventStore history;
    private final ConfirmationMatcher matcher;
    private final ConfirmationStrengthClassifier classifier;
    private final TriggerRequirementPolicy policy;
    private final SignalExplanationBuilder explanations = new SignalExplanationBuilder();
    private final LongSupplier receiptClock;
    private final Map<String, SignalCandidate> candidates = new LinkedHashMap<>();

    public SignalComposer(String alias, long epoch, SignalComposerConfig config) {
        this(alias, epoch, config, System::currentTimeMillis);
    }
    public SignalComposer(String alias, long epoch, SignalComposerConfig config, LongSupplier receiptClock) {
        if (!config.valid) throw new IllegalArgumentException(config.error);
        this.alias = alias; this.epoch = epoch; this.config = config; this.receiptClock = receiptClock;
        history = new PatternEventStore(alias, epoch, config);
        matcher = new ConfirmationMatcher(config); classifier = new ConfirmationStrengthClassifier(config);
        policy = new TriggerRequirementPolicy(config);
    }
    public CompositionUpdate onPatternEvent(PatternEvent event) {
        List<TradingSignal> signals = new ArrayList<>();
        List<CompositionUpdate.CandidateTransition> transitions = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        if (!alias.equals(event.instrumentAlias) || event.epoch != epoch || !config.eligible(alias)
                || event.timestampProvenance != PatternEvent.TimestampProvenance.MARKET
                || event.evidence.coverage != PatternEvent.Coverage.USABLE) {
            diagnostics.add("Unusable event context/readiness: " + event.id); return update(signals, transitions, diagnostics);
        }
        nowNs = Math.max(nowNs, event.observedAtNs);
        history.prune(nowNs);
        maintain(transitions);
        PatternEventStore.Change change = history.put(event, nowNs);
        maintain(transitions);
        if (change == PatternEventStore.Change.REJECTED || change == PatternEventStore.Change.DUPLICATE || history.get(event.id) == null) {
            return update(signals, transitions, diagnostics);
        }
        boolean bid = event.meaning == PatternMeaning.BID_HOLD || event.meaning == PatternMeaning.BID_FAIL;
        if (bid) {
            for (SignalCandidate existing : candidates.values()) {
                if (active(existing) && existing.direction != TradingSignal.directionFrom(event)
                        && event.eventTimeNs >= existing.trigger.eventTimeNs
                        && Math.abs((long)event.priceTick - existing.trigger.priceTick) <= config.maxPriceDistanceTicks) {
                    transition(existing, SignalState.INVALID, "Opposing bid behavior in local interaction", transitions);
                }
            }
        }
        if (bid && event.size >= config.minimumTriggerSize) {
            SignalCandidate proposed = new SignalCandidate(event, config.afterWindowMs);
            SignalCandidate candidate = candidates.get(proposed.key);
            if (candidate == null && event.revision > 1) {
                diagnostics.add("Revised trigger has no active original candidate: " + proposed.key);
                return update(signals, transitions, diagnostics);
            }
            if (candidate == null) { candidate = proposed; candidates.put(candidate.key, candidate); enforceCapacity(transitions); }
            else { candidate.addBidEvidence(event); }
            evaluate(candidate, signals, transitions, diagnostics);
        } else if (bid) { diagnostics.add("Trigger below absolute minimum: " + event.id); }
        else if (event.meaning == PatternMeaning.OFFER_BEARISH_CONFIRMATION || event.meaning == PatternMeaning.OFFER_BULLISH_CONFIRMATION) {
            for (SignalCandidate candidate : candidates.values()) evaluate(candidate, signals, transitions, diagnostics);
        }
        return update(signals, transitions, diagnostics);
    }
    private void evaluate(SignalCandidate candidate, List<TradingSignal> signals,
            List<CompositionUpdate.CandidateTransition> transitions, List<String> diagnostics) {
        if (candidate.state == SignalState.INVALID || candidate.state == SignalState.EXPIRED) return;
        if (nowNs > candidate.expiresAtNs) {
            transition(candidate, SignalState.EXPIRED, "Trigger outside active window", transitions); return;
        }
        List<ConfirmationMatch> matches = matcher.find(candidate.trigger, history.snapshot(), nowNs);
        ConfirmationStrengthClassifier.Selection selection = classifier.select(matches);
        if (candidate.signal != null) { reviseEvidence(candidate, matches, signals); return; }
        long required = policy.requiredSize(selection.strength);
        if (!policy.accepts(candidate.trigger.size, selection.strength)) {
            diagnostics.add("Waiting for sufficient confirmation: " + candidate.key + "; trigger=" + candidate.trigger.size + "; required=" + required);
            return;
        }
        TradingSignal.FirstValidation validation = new TradingSignal.FirstValidation(nowNs, config.normalTriggerSize,
                required, selection.strength, matches, selection.strongest);
        candidate.signal = new TradingSignal(candidate.trigger, validation, Collections.emptyList(), candidate.supportingBids,
                selection.strength, 1, receiptClock.getAsLong(), config.revision,
                explanations.build(candidate.trigger, validation, Collections.emptyList()));
        SignalState previous = candidate.state; candidate.state = SignalState.VALID;
        transitions.add(new CompositionUpdate.CandidateTransition(candidate.key, previous, candidate.state, "Bid trigger meets applied threshold " + required));
        signals.add(candidate.signal);
    }
    private void reviseEvidence(SignalCandidate candidate, List<ConfirmationMatch> matches, List<TradingSignal> signals) {
        TradingSignal previous = candidate.signal;
        Map<String, ConfirmationMatch> subsequent = new LinkedHashMap<>();
        for (ConfirmationMatch match : previous.subsequentConfirmations) subsequent.put(match.event.id, match);
        boolean changed = !previous.supportingBidEvidence.equals(candidate.supportingBids);
        for (ConfirmationMatch match : matches) {
            int knownRevision = 0;
            for (ConfirmationMatch initial : previous.firstValidation.confirmations) {
                if (initial.event.id.equals(match.event.id)) knownRevision = initial.event.revision;
            }
            ConfirmationMatch recorded = subsequent.get(match.event.id);
            if (recorded != null) knownRevision = Math.max(knownRevision, recorded.event.revision);
            if (match.event.revision > knownRevision) { subsequent.put(match.event.id, match); changed = true; }
        }
        if (!changed) return;
        List<ConfirmationMatch> additions = new ArrayList<>(subsequent.values());
        List<ConfirmationMatch> all = new ArrayList<>(previous.firstValidation.confirmations); all.addAll(additions);
        ConfirmationStrength strength = classifier.select(all).strength;
        candidate.signal = new TradingSignal(candidate.trigger, previous.firstValidation, additions, candidate.supportingBids,
                strength, previous.revision + 1, previous.createdAtMs, previous.configRevision,
                explanations.build(candidate.trigger, previous.firstValidation, additions));
        signals.add(candidate.signal);
    }
    private CompositionUpdate update(List<TradingSignal> signals, List<CompositionUpdate.CandidateTransition> transitions, List<String> diagnostics) {
        return new CompositionUpdate(signals, Collections.emptyMap(), transitions, diagnostics, nowNs);
    }
    public Map<String, SignalState> candidateStates() {
        Map<String, SignalState> copy = new LinkedHashMap<>(); candidates.forEach((key, value) -> copy.put(key, value.state));
        return Collections.unmodifiableMap(copy);
    }
    private static boolean active(SignalCandidate candidate) {
        return candidate.state == SignalState.CANDIDATE || candidate.state == SignalState.VALID;
    }
    private void transition(SignalCandidate candidate, SignalState state, String reason, List<CompositionUpdate.CandidateTransition> transitions) {
        if (candidate.state == state) return;
        transitions.add(new CompositionUpdate.CandidateTransition(candidate.key, candidate.state, state, reason)); candidate.state = state;
    }
    private void maintain(List<CompositionUpdate.CandidateTransition> transitions) {
        for (SignalCandidate candidate : candidates.values()) {
            if (!active(candidate)) continue;
            if (nowNs > candidate.expiresAtNs) transition(candidate, SignalState.EXPIRED, "Active trigger window expired", transitions);
            else if (history.get(candidate.trigger.id) == null) transition(candidate, SignalState.INVALID, "Trigger evidence was evicted", transitions);
        }
    }
    private void enforceCapacity(List<CompositionUpdate.CandidateTransition> transitions) {
        while (candidates.size() > config.maxCandidates) {
            String oldest = candidates.keySet().iterator().next();
            SignalCandidate removed = candidates.remove(oldest);
            if (active(removed)) transition(removed, SignalState.EXPIRED, "Candidate capacity eviction", transitions);
        }
    }
    public CompositionUpdate onMarketTime(long timeNs) {
        if (timeNs <= 0 || timeNs < nowNs) throw new IllegalArgumentException("Reset observation epoch before backwards/invalid market time");
        nowNs = timeNs; history.prune(nowNs);
        List<CompositionUpdate.CandidateTransition> transitions = new ArrayList<>(); maintain(transitions);
        return update(Collections.emptyList(), transitions, Collections.emptyList());
    }
    public CompositionUpdate onMarketPrice(int bidTick, int askTick, int lastTradeTick) {
        List<CompositionUpdate.CandidateTransition> transitions = new ArrayList<>();
        if (bidTick > 0 && askTick > 0 && bidTick > askTick) return update(Collections.emptyList(), transitions, Collections.emptyList());
        int price = bidTick > 0 && askTick > 0 ? (int)(((long)bidTick + askTick) / 2)
                : bidTick > 0 ? bidTick : askTick > 0 ? askTick : lastTradeTick;
        if (price > 0) for (SignalCandidate candidate : candidates.values()) {
            if (active(candidate) && Math.abs((long)price - candidate.trigger.priceTick) > config.maxTriggerDriftTicks) {
                transition(candidate, SignalState.INVALID, "Market price left the local trigger interaction", transitions);
            }
        }
        return update(Collections.emptyList(), transitions, Collections.emptyList());
    }
    public CompositionUpdate reset(ResetReason reason, long nextEpoch) {
        if (nextEpoch <= epoch) throw new IllegalArgumentException("Reset requires a new epoch");
        List<CompositionUpdate.CandidateTransition> transitions = new ArrayList<>();
        for (SignalCandidate candidate : candidates.values()) if (active(candidate)) transition(candidate, SignalState.EXPIRED, "Observation reset: " + reason, transitions);
        candidates.clear(); history.reset(nextEpoch); epoch = nextEpoch; nowNs = 0;
        return update(Collections.emptyList(), transitions, Collections.singletonList("Observation reset: " + reason));
    }
}
