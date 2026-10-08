package com.bookmap.plugin.rong.signal;

import com.bookmap.plugin.rong.patterns.PatternEvent;
import com.google.gson.*;
import java.util.*;

/** Bounded aggregate projection. No raw depth, trade, or quote events leave this boundary. */
public final class CairoPatternSnapshot {
    private static final Gson GSON = new Gson();
    private final String alias, source = UUID.randomUUID().toString(), mode, configRevision;
    private final double tickSize;
    private long epoch = 1, sequence;
    private final Map<String, PatternEvent> patterns = new LinkedHashMap<>();
    private final Map<String, TradingSignal> signals = new LinkedHashMap<>();
    private final Map<String, Long> stateTimes = new LinkedHashMap<>();
    private final Map<String, String> states = new LinkedHashMap<>();
    private final Map<String, String> signalVersions = new LinkedHashMap<>();
    private final Map<String, Integer> wireRevisions = new LinkedHashMap<>();
    private CompositionUpdate latest;
    public CairoPatternSnapshot(String alias, double tickSize, String mode, String configRevision) {
        this.alias = alias; this.tickSize = tickSize; this.mode = mode; this.configRevision = configRevision;
    }
    public void reset(long epoch) { this.epoch = epoch; patterns.clear(); signals.clear(); states.clear(); stateTimes.clear(); signalVersions.clear(); wireRevisions.clear(); latest = null; }
    public void event(PatternEvent event) {
        if (event.epoch != epoch) reset(event.epoch);
        patterns.put(event.id, event); bound(patterns, 64);
    }
    public void update(CompositionUpdate update) {
        latest = update;
        for (TradingSignal signal : update.signals) { signals.put(signal.id, signal); bound(signals, 32); }
        for (CompositionUpdate.CandidateTransition transition : update.transitions) {
            states.put(transition.candidateId, transition.current.name()); stateTimes.put(transition.candidateId, update.marketTimeNs); bound(states, 128);
            stateTimes.keySet().retainAll(states.keySet());
        }
    }
    private static void bound(Map<?, ?> map, int max) { while (map.size() > max) map.remove(map.keySet().iterator().next()); }
    public JsonObject snapshot(boolean ready, long marketTimeNs, JsonObject liquidity, boolean reconnect) {
        patterns.values().removeIf(event -> marketTimeNs - event.observedAtNs > 600_000_000_000L);
        if (latest != null) latest.contexts.values().forEach(context -> event(context.confirmation));
        JsonObject value = new JsonObject();
        value.addProperty("type", "cairo_patterns"); value.addProperty("version", 1);
        value.addProperty("sourceInstanceId", source); value.addProperty("symbol", alias);
        value.addProperty("epoch", epoch); value.addProperty("sequence", ++sequence);
        value.addProperty("eventTime", Long.toString(marketTimeNs)); value.addProperty("priceUnit", "USD");
        value.addProperty("tickSize", tickSize); value.addProperty("mode", mode);
        value.addProperty("readiness", ready ? "ready" : "not-ready");
        value.addProperty("coverage", ready ? "continuous" : "warming");
        value.addProperty("delivery", reconnect ? "snapshot" : "stream");
        value.addProperty("detectorRevision", "composer-patterns-v1"); value.addProperty("configRevision", configRevision);
        JsonArray events = new JsonArray();
        for (PatternEvent event : patterns.values()) {
            JsonObject e = new JsonObject();
            e.addProperty("id", event.id); e.addProperty("revision", event.revision); e.addProperty("type", event.type.name());
            e.addProperty("side", event.side.name()); e.addProperty("meaning", event.meaning.name());
            e.addProperty("interactionId", event.interactionId); e.addProperty("episodeKey", event.episodeKey);
            e.addProperty("size", event.size); e.addProperty("sizeBasis", event.sizeBasis.name()); e.addProperty("price", event.price);
            e.addProperty("eventTime", Long.toString(event.eventTimeNs)); e.addProperty("observedAt", Long.toString(event.observedAtNs));
            e.addProperty("timestampProvenance", event.timestampProvenance.name());
            JsonObject evidence = GSON.toJsonTree(event.evidence).getAsJsonObject();
            evidence.addProperty("approachTimeNs", Long.toString(event.evidence.approachTimeNs));
            evidence.addProperty("rejectionTimeNs", Long.toString(event.evidence.rejectionTimeNs)); e.add("evidence", evidence);
            events.add(e);
        }
        value.add("patterns", events);
        JsonArray accepted = new JsonArray();
        for (TradingSignal signal : signals.values()) {
            String candidateKey = signal.trigger.instrumentAlias + ":" + signal.trigger.epoch + ":" + signal.direction + ":" + signal.trigger.interactionId;
            String state = states.getOrDefault(candidateKey, "EXPIRED");
            String version = signal.revision + ":" + state + ":" + stateTimes.getOrDefault(candidateKey, signal.firstValidation.eventTimeNs);
            if (!version.equals(signalVersions.get(signal.id))) {
                signalVersions.put(signal.id, version); wireRevisions.put(signal.id, wireRevisions.getOrDefault(signal.id, 0) + 1);
            }
            JsonObject s = new JsonObject(); s.addProperty("id", signal.id); s.addProperty("revision", wireRevisions.get(signal.id));
            s.addProperty("compositionRevision", signal.revision);
            s.addProperty("direction", signal.direction.name()); s.addProperty("triggerId", signal.trigger.id);
            s.addProperty("interactionId", signal.trigger.interactionId);
            s.addProperty("pattern", signal.trigger.type.name()); s.addProperty("price", signal.trigger.price);
            s.addProperty("size", signal.trigger.size); s.addProperty("state", states.getOrDefault(signal.trigger.instrumentAlias + ":" + signal.trigger.epoch + ":" + signal.direction + ":" + signal.trigger.interactionId, "EXPIRED"));
            s.addProperty("eventTime", Long.toString(signal.firstValidation.eventTimeNs));
            long observedAt = signal.firstValidation.eventTimeNs;
            for (com.bookmap.plugin.rong.signal.ConfirmationMatch match : signal.confirmations) observedAt = Math.max(observedAt, match.event.observedAtNs);
            for (PatternEvent bid : signal.supportingBidEvidence) observedAt = Math.max(observedAt, bid.observedAtNs);
            s.addProperty("observedAt", Long.toString(observedAt));
            s.addProperty("stateAt", Long.toString(stateTimes.getOrDefault(signal.trigger.instrumentAlias + ":" + signal.trigger.epoch + ":" + signal.direction + ":" + signal.trigger.interactionId, observedAt)));
            s.addProperty("explanation", signal.explanation); s.addProperty("confirmationStrength", signal.latestConfirmationStrength.name());
            s.addProperty("normalTriggerThreshold", signal.firstValidation.normalTriggerThreshold);
            s.addProperty("appliedTriggerThreshold", signal.firstValidation.appliedTriggerThreshold);
            Set<String> evidenceIds = new LinkedHashSet<>(); evidenceIds.add(signal.trigger.id);
            signal.confirmations.forEach(match -> evidenceIds.add(match.event.id));
            JsonArray ids = new JsonArray(); evidenceIds.forEach(ids::add); s.add("evidenceIds", ids);
            if (patterns.containsKey(signal.trigger.id) && signal.confirmations.stream().allMatch(match -> patterns.containsKey(match.event.id))) accepted.add(s);
        }
        signalVersions.keySet().retainAll(signals.keySet()); wireRevisions.keySet().retainAll(signals.keySet());
        value.add("signals", accepted);
        JsonArray contexts = new JsonArray();
        if (latest != null) latest.contexts.values().forEach(context -> {
            JsonObject c = new JsonObject(); c.addProperty("direction", context.direction.name());
            c.addProperty("confirmationId", context.confirmation.id); c.addProperty("strength", context.strength.name());
            c.addProperty("waitingFor", context.waitingFor.name()); c.addProperty("requiredTriggerSize", context.requiredTriggerSize);
            c.addProperty("expiresAt", Long.toString(context.expiresAtNs)); contexts.add(c);
        });
        value.add("contexts", contexts); value.add("candidateStates", GSON.toJsonTree(states)); value.add("liquidity", liquidity);
        return value;
    }
}
