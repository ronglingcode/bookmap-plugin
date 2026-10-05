package com.bookmap.plugin.rong.patterns;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import com.bookmap.plugin.rong.SymbolUtils;
import com.google.gson.JsonObject;

/** Bounded observation serialization. No broker or trading action dependencies. */
public final class CairoObservationExport {
    private final String instance = UUID.randomUUID().toString();
    private long sequence;
    private final Map<String, Integer> revisions = new LinkedHashMap<>();
    public static boolean allowed(PatternType type) {
        return type == PatternType.BID_STEP_UP || type == PatternType.BID_REAPPEAR;
    }
    public synchronized JsonObject episode(BookmapPatternSignal signal) {
        if (!allowed(signal.getPatternType()) || !Double.isFinite(signal.getTriggerPrice()) || signal.getTriggerPrice() <= 0) return null;
        String key = signal.getInstrumentAlias() + ":" + signal.getEpisodeKey();
        int revision = revisions.getOrDefault(key, 0) + 1;
        revisions.put(key, revision);
        while (revisions.size() > 400) revisions.remove(revisions.keySet().iterator().next());
        JsonObject value = envelope(signal.getInstrumentAlias(), "episode", "live");
        value.addProperty("episodeId", signal.getEpisodeKey());
        value.addProperty("revision", revision);
        value.addProperty("pattern", signal.getPatternType().name());
        value.addProperty("price", signal.getTriggerPrice());
        value.addProperty("eventTime", Long.toString(signal.getEventTimeNs()));
        return value;
    }
    public synchronized JsonObject envelope(String alias, String kind, String delivery) {
        JsonObject value = new JsonObject();
        value.addProperty("type", "cairo_observation");
        value.addProperty("sourceInstanceId", instance);
        value.addProperty("sequence", ++sequence);
        JsonObject symbol = new JsonObject(); symbol.addProperty("source", alias); symbol.addProperty("canonical", SymbolUtils.cleanSymbol(alias)); value.add("symbol", symbol);
        value.addProperty("priceUnit", "USD"); value.addProperty("episodeId", "source-status"); value.addProperty("revision", 0);
        value.addProperty("pattern", "status"); value.add("price", com.google.gson.JsonNull.INSTANCE); value.add("eventTime", com.google.gson.JsonNull.INSTANCE);
        value.addProperty("receivedAt", Long.toString(System.currentTimeMillis() * 1_000_000L));
        value.addProperty("detectorRevision", "bmtrader-1.31-bid-v1"); value.addProperty("configRevision", "observer-v1");
        value.addProperty("mode", "unknown"); value.addProperty("readiness", "unknown");
        value.addProperty("delivery", delivery); value.addProperty("kind", kind);
        return value;
    }
    public synchronized JsonObject snapshot(JsonObject episode) {
        JsonObject copy = episode.deepCopy(); copy.addProperty("sequence", ++sequence); copy.addProperty("delivery", "snapshot"); return copy;
    }
}
