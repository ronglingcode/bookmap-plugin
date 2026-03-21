package com.bookmap.plugin.common;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks premarket high and low prices per instrument.
 * Updates price lines in the store as new extremes are hit during premarket hours.
 *
 * Premarket session: 4:00 AM - 9:30 AM Eastern Time.
 * Lines persist after premarket ends so they serve as reference levels during regular hours.
 * Resets at the start of each new premarket session (4:00 AM ET).
 */
public class PremarketTracker implements IndicatorConfig.ChangeListener {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalTime PREMARKET_START = LocalTime.of(4, 0);
    private static final LocalTime PREMARKET_END = LocalTime.of(9, 30);

    private final PriceLineStore store;
    private final IndicatorConfig config;

    /** Per-instrument tracking state. */
    private final Map<String, PremarketState> states = new ConcurrentHashMap<>();

    public PremarketTracker(PriceLineStore store, IndicatorConfig config) {
        this.store = store;
        this.config = config;
        config.addChangeListener(this);
    }

    /** Current data/replay time in epoch nanoseconds, updated via onTimestamp(). */
    private volatile long currentTimestampNs;

    /** Called by the plugin's TimeListener to keep the tracker in sync with data/replay time. */
    public void setTimestamp(long timestampNs) {
        this.currentTimestampNs = timestampNs;
    }

    /**
     * Called on each trade. Updates premarket high/low if currently in premarket hours.
     * Uses the data/replay timestamp (set via setTimestamp) instead of system clock.
     *
     * @param instrumentAlias instrument identifier
     * @param priceTick       price in tick units (for canvas coordinates)
     * @param realPrice       price in real units (for display)
     */
    public void onTrade(String instrumentAlias, double priceTick, double realPrice) {
        if (!config.isEnabled(IndicatorConfig.PREMARKET_HIGH_LOW)) return;
        if (currentTimestampNs == 0) return; // no timestamp received yet

        ZonedDateTime now = Instant.ofEpochSecond(0, currentTimestampNs).atZone(ET);
        LocalTime timeET = now.toLocalTime();

        PremarketState state = states.computeIfAbsent(instrumentAlias, k -> new PremarketState());

        // Reset at the start of a new premarket session
        int today = now.getDayOfYear();
        if (state.lastSessionDay != today && !timeET.isBefore(PREMARKET_START)) {
            state.reset();
            state.lastSessionDay = today;
            // Remove old lines when new session starts
            store.removeByType(instrumentAlias, PriceLine.LineType.PREMARKET_HIGH);
            store.removeByType(instrumentAlias, PriceLine.LineType.PREMARKET_LOW);
        }

        // Only update during premarket hours
        if (timeET.isBefore(PREMARKET_START) || !timeET.isBefore(PREMARKET_END)) return;

        boolean changed = false;

        if (Double.isNaN(state.highPrice) || realPrice > state.highPrice) {
            state.highPrice = realPrice;
            state.highPriceTick = priceTick;
            PriceLine highLine = new PriceLine(instrumentAlias, PriceLine.LineType.PREMARKET_HIGH,
                    priceTick, realPrice);
            store.replaceByType(instrumentAlias, PriceLine.LineType.PREMARKET_HIGH, highLine);
            changed = true;
        }

        if (Double.isNaN(state.lowPrice) || realPrice < state.lowPrice) {
            state.lowPrice = realPrice;
            state.lowPriceTick = priceTick;
            PriceLine lowLine = new PriceLine(instrumentAlias, PriceLine.LineType.PREMARKET_LOW,
                    priceTick, realPrice);
            store.replaceByType(instrumentAlias, PriceLine.LineType.PREMARKET_LOW, lowLine);
            changed = true;
        }
    }

    /** Remove tracking state for an instrument. */
    public void unregister(String instrumentAlias) {
        states.remove(instrumentAlias);
    }

    @Override
    public void onIndicatorConfigChanged(String indicatorKey, boolean enabled) {
        if (IndicatorConfig.PREMARKET_HIGH_LOW.equals(indicatorKey) && !enabled) {
            // Remove all premarket lines when disabled
            for (String alias : states.keySet()) {
                store.removeByType(alias, PriceLine.LineType.PREMARKET_HIGH);
                store.removeByType(alias, PriceLine.LineType.PREMARKET_LOW);
            }
        }
    }

    public void shutdown() {
        config.removeChangeListener(this);
        states.clear();
    }

    private static class PremarketState {
        double highPrice = Double.NaN;
        double lowPrice = Double.NaN;
        double highPriceTick = Double.NaN;
        double lowPriceTick = Double.NaN;
        int lastSessionDay = -1;

        void reset() {
            highPrice = Double.NaN;
            lowPrice = Double.NaN;
            highPriceTick = Double.NaN;
            lowPriceTick = Double.NaN;
        }
    }
}
