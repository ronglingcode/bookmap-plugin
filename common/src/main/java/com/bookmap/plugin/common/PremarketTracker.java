package com.bookmap.plugin.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import velox.api.layer1.datastructure.events.TradeAggregationEvent;
import velox.api.layer1.messages.indicators.DataStructureInterface;
import velox.api.layer1.messages.indicators.DataStructureInterface.StandardEvents;
import velox.api.layer1.messages.indicators.DataStructureInterface.TreeResponseInterval;

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

    /**
     * Backfill premarket high/low from historical trade data.
     * Called once during initialization to catch up on trades that happened
     * before the plugin was attached (e.g. user turns on computer mid-premarket).
     *
     * @param dataInterface Bookmap's historical data query interface
     * @param instrumentAlias instrument identifier
     * @param pips price multiplier to convert ticks to real price
     * @param currentTimeNs current data time in epoch nanoseconds
     */
    public void backfillFromHistory(DataStructureInterface dataInterface, String instrumentAlias,
                                     double pips, long currentTimeNs) {
        if (!config.isEnabled(IndicatorConfig.PREMARKET_HIGH_LOW)) return;

        // Calculate today's premarket time range in nanoseconds
        ZonedDateTime now = Instant.ofEpochSecond(0, currentTimeNs).atZone(ET);
        LocalDate today = now.toLocalDate();
        long premarketStartNs = today.atTime(PREMARKET_START).atZone(ET).toInstant().toEpochMilli() * 1_000_000L;
        long premarketEndNs = today.atTime(PREMARKET_END).atZone(ET).toInstant().toEpochMilli() * 1_000_000L;

        // Clamp the query end to the lesser of premarket end or current time
        long queryEndNs = Math.min(premarketEndNs, currentTimeNs);
        if (queryEndNs <= premarketStartNs) return; // no premarket data to query

        try {
            // Query trade data across the premarket window, 1 interval = full aggregation
            List<TreeResponseInterval> intervals = dataInterface.get(
                    premarketStartNs, queryEndNs, 1, instrumentAlias,
                    new StandardEvents[]{StandardEvents.TRADE});

            if (intervals == null || intervals.isEmpty()) return;

            double highPrice = Double.NaN;
            double lowPrice = Double.NaN;
            double highPriceTick = Double.NaN;
            double lowPriceTick = Double.NaN;

            for (TreeResponseInterval interval : intervals) {
                if (interval.events == null) continue;
                for (Object eventObj : interval.events.values()) {
                    if (!(eventObj instanceof TradeAggregationEvent)) continue;
                    TradeAggregationEvent trade = (TradeAggregationEvent) eventObj;

                    // Scan all traded prices from both bid and ask aggressor maps
                    for (Map<Double, ?> priceMap : new Map[]{trade.bidAggressorMap, trade.askAggressorMap}) {
                        if (priceMap == null) continue;
                        for (Double priceTick : priceMap.keySet()) {
                            if (priceTick == null) continue;
                            double realPrice = priceTick * pips;
                            if (Double.isNaN(highPrice) || realPrice > highPrice) {
                                highPrice = realPrice;
                                highPriceTick = priceTick;
                            }
                            if (Double.isNaN(lowPrice) || realPrice < lowPrice) {
                                lowPrice = realPrice;
                                lowPriceTick = priceTick;
                            }
                        }
                    }
                }
            }

            if (!Double.isNaN(highPrice)) {
                PremarketState state = states.computeIfAbsent(instrumentAlias, k -> new PremarketState());
                state.lastSessionDay = today.getDayOfYear();
                state.highPrice = highPrice;
                state.highPriceTick = highPriceTick;
                state.lowPrice = lowPrice;
                state.lowPriceTick = lowPriceTick;

                PriceLine highLine = new PriceLine(instrumentAlias, PriceLine.LineType.PREMARKET_HIGH,
                        highPriceTick, highPrice);
                store.replaceByType(instrumentAlias, PriceLine.LineType.PREMARKET_HIGH, highLine);

                PriceLine lowLine = new PriceLine(instrumentAlias, PriceLine.LineType.PREMARKET_LOW,
                        lowPriceTick, lowPrice);
                store.replaceByType(instrumentAlias, PriceLine.LineType.PREMARKET_LOW, lowLine);

                System.out.println("[PremarketTracker] Backfilled " + instrumentAlias
                        + ": PM High=" + highPrice + ", PM Low=" + lowPrice);
            }
        } catch (Exception e) {
            System.err.println("[PremarketTracker] Backfill failed for " + instrumentAlias + ": " + e.getMessage());
            e.printStackTrace();
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
