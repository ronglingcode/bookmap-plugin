package com.bookmap.plugin.rong;

import java.util.ArrayList;
import java.util.List;

/** Stores authoritative closed-minute VWAP values received from ViteApp. */
public final class VwapTracker {

    private static final long NS_PER_MS = 1_000_000L;

    public static final class VwapPoint {
        private final long timestampNs;
        private final double value;

        private VwapPoint(long timestampNs, double value) {
            this.timestampNs = timestampNs;
            this.value = value;
        }

        public long getTimestampNs() {
            return timestampNs;
        }

        public double getValue() {
            return value;
        }
    }

    private final String symbol;
    private final List<VwapPoint> pendingIndicatorPoints = new ArrayList<>();
    private VwapUpdateDefinition activeUpdate;

    public VwapTracker(String symbol) {
        this.symbol = SymbolUtils.cleanSymbol(symbol);
    }

    public synchronized boolean applyUpdate(VwapUpdateDefinition update) {
        if (update == null || !symbol.equals(update.getSymbol())) {
            return false;
        }
        if (activeUpdate != null
                && (update.getEffectiveTimeMs() < activeUpdate.getEffectiveTimeMs()
                || (update.getEffectiveTimeMs() == activeUpdate.getEffectiveTimeMs()
                && update.getSentAtMs() <= activeUpdate.getSentAtMs()))) {
            return false;
        }

        activeUpdate = update;
        pendingIndicatorPoints.add(new VwapPoint(
                update.getEffectiveTimeMs() * NS_PER_MS,
                update.getVwap()));
        return true;
    }

    public synchronized List<VwapPoint> drainPendingIndicatorPoints() {
        List<VwapPoint> result = new ArrayList<>(pendingIndicatorPoints);
        pendingIndicatorPoints.clear();
        return result;
    }

    public synchronized void requestCurrentPoint(long timestampNs) {
        if (activeUpdate != null && timestampNs > 0) {
            pendingIndicatorPoints.add(new VwapPoint(timestampNs, activeUpdate.getVwap()));
        }
    }

    public synchronized double getCurrentVwap() {
        return activeUpdate == null ? Double.NaN : activeUpdate.getVwap();
    }
}
