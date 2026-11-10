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
    public CairoPatternSnapshot(String alias, double tickSize, String mode, String configRevision) {
        this.alias = alias; this.tickSize = tickSize; this.mode = mode; this.configRevision = configRevision;
    }
    public void reset(long epoch) { this.epoch = epoch; patterns.clear(); }
    public void event(PatternEvent event) {
        if (event.epoch != epoch) reset(event.epoch);
        patterns.put(event.id, event); bound(patterns, 64);
    }
    private static void bound(Map<?, ?> map, int max) { while (map.size() > max) map.remove(map.keySet().iterator().next()); }
    public JsonObject snapshot(boolean ready, long marketTimeNs, JsonObject liquidity, boolean reconnect) {
        patterns.values().removeIf(event -> marketTimeNs - event.observedAtNs > 600_000_000_000L);
        JsonObject value = new JsonObject();
        value.addProperty("type", "cairo_patterns"); value.addProperty("version", 2);
        value.addProperty("sourceInstanceId", source); value.addProperty("symbol", alias);
        value.addProperty("epoch", epoch); value.addProperty("sequence", ++sequence);
        value.addProperty("eventTime", Long.toString(marketTimeNs)); value.addProperty("priceUnit", "USD");
        value.addProperty("tickSize", tickSize); value.addProperty("mode", mode);
        value.addProperty("readiness", ready ? "ready" : "not-ready");
        value.addProperty("coverage", ready ? "continuous" : "warming");
        value.addProperty("delivery", reconnect ? "snapshot" : "stream");
        value.addProperty("detectorRevision", "pattern-events-v2"); value.addProperty("configRevision", configRevision);
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
            evidence.addProperty("growthMaxGapNs", Long.toString(event.evidence.growthMaxGapNs));
            evidence.addProperty("growthStartNs", Long.toString(event.evidence.growthStartNs));
            evidence.addProperty("growthEndNs", Long.toString(event.evidence.growthEndNs));
            evidence.addProperty("growthDurationNs", Long.toString(event.evidence.growthDurationNs));
            evidence.addProperty("approachTimeNs", Long.toString(event.evidence.approachTimeNs));
            evidence.addProperty("rejectionTimeNs", Long.toString(event.evidence.rejectionTimeNs)); e.add("evidence", evidence);
            events.add(e);
        }
        value.add("patterns", events);
        value.add("liquidity", liquidity);
        return value;
    }
}
