package com.bookmap.plugin.rong.miniviteapp.core.controllers;

import com.google.gson.*;
import java.util.*;
import static com.bookmap.plugin.rong.miniviteapp.models.DomainJson.*;

/** Advances only on successful broker observations. Mirror: stopLossMonitor.ts. */
public final class StopLossMonitor {
    private static final class Exposure {
        final long since;
        final boolean isLong;
        boolean closing;
        Exposure(long since, boolean isLong) { this.since = since; this.isLong = isLong; }
    }
    public static final class Decision {
        public final Map<String, Double> warnings = new LinkedHashMap<>();
        public final List<String> close = new ArrayList<>();
    }
    private final Map<String, Exposure> exposed = new HashMap<>();
    public synchronized Decision observe(JsonObject account, long now) {
        Decision result = new Decision();
        JsonObject positions = object(account, "positions");
        for (String symbol : positions.keySet()) {
            double net = number(object(positions, symbol), "netQuantity"), covered = 0;
            Set<String> ids = new HashSet<>();
            for (JsonElement item : array(object(account, "exitPairs"), symbol)) {
                JsonObject stop = object(item.getAsJsonObject(), "STOP");
                String id = string(stop, "orderID"), status = string(object(stop, "rawOrder"), "status");
                double quantity = number(stop, "quantity");
                if (id.isEmpty() || ids.contains(id) || bool(stop, "isBuy") != (net < 0)
                        || !Double.isFinite(quantity) || quantity <= 0 || !(number(stop, "price") > 0)
                        || Set.of("AWAITING_PARENT_ORDER", "CANCELED", "REPLACED", "REJECTED", "EXPIRED", "FILLED").contains(status)) continue;
                ids.add(id); covered += quantity;
            }
            double uncovered = Math.abs(net) - covered;
            if (!Double.isFinite(net) || net == 0 || uncovered <= 0) { exposed.remove(symbol); continue; }
            Exposure exposure = exposed.get(symbol);
            if (exposure == null || exposure.isLong != (net > 0)) {
                exposure = new Exposure(now, net > 0); exposed.put(symbol, exposure); result.warnings.put(symbol, uncovered);
            }
            if (!exposure.closing && now - exposure.since > 10000) { exposure.closing = true; result.close.add(symbol); }
        }
        exposed.keySet().retainAll(positions.keySet());
        return result;
    }
}
