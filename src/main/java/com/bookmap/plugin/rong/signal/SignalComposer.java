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
        PatternEventStore.Change change = history.put(event, nowNs);
        if (change == PatternEventStore.Change.REJECTED || change == PatternEventStore.Change.DUPLICATE || history.get(event.id) == null) {
            return update(signals, transitions, diagnostics);
        }
        boolean bid = event.meaning == PatternMeaning.BID_HOLD || event.meaning == PatternMeaning.BID_FAIL;
        if (bid && event.size >= config.minimumTriggerSize) {
            SignalCandidate candidate = new SignalCandidate(event, config.afterWindowMs);
            candidates.put(event.id, candidate);
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
        if (nowNs > candidate.expiresAtNs) { diagnostics.add("Trigger outside active window: " + candidate.key); return; }
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
        boolean changed = false;
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
}
