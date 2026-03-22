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
 * Tracks Volume Weighted Average Price (VWAP) per instrument and draws it as a price line on the chart.
 *
 * <h3>What is VWAP?</h3>
 * <p>VWAP = cumulative(price * volume) / cumulative(volume)</p>
 * <p>It represents the average price a security has traded at throughout the day,
 * weighted by volume. Institutional traders use VWAP as a benchmark — buying below VWAP
 * or selling above VWAP is generally considered favorable execution.</p>
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>The plugin's {@code TimeListener.onTimestamp()} calls {@link #setTimestamp(long)} on every
 *       tick to keep this tracker in sync with data/replay time (NOT system clock).</li>
 *   <li>The plugin's {@code TradeDataListener.onTrade()} calls {@link #onTrade} on every trade.
 *       The tracker accumulates price*volume and volume, then computes the running VWAP
 *       and updates the price line in the {@link PriceLineStore}.</li>
 *   <li>On initialization, {@link #backfillFromHistory} queries Bookmap's historical data API
 *       to catch up on trades that happened before the plugin was attached.</li>
 * </ol>
 *
 * <h3>Key differences from PremarketTracker</h3>
 * <ul>
 *   <li>VWAP runs the <b>entire trading day</b> (4:00 AM ET onward), not just premarket hours.</li>
 *   <li>VWAP needs trade volume (size), not just price.</li>
 *   <li>VWAP produces a single line that moves with each trade (vs. premarket's two static lines).</li>
 *   <li>VWAP resets daily at 4:00 AM ET when a new trading session begins.</li>
 * </ul>
 *
 * <h3>Daily reset</h3>
 * <p>Like the PremarketTracker, VWAP resets at 4:00 AM ET each day. This means:
 * <ul>
 *   <li>Trades between midnight and 4:00 AM do NOT trigger a reset — the previous day's
 *       VWAP line remains visible as a reference.</li>
 *   <li>At 4:00 AM ET, the first trade on the new day triggers a reset: cumulative totals
 *       are cleared and a fresh VWAP calculation begins.</li>
 *   <li>VWAP continues updating through premarket, market open, and regular hours until
 *       the next day's reset.</li>
 * </ul></p>
 *
 * <h3>Multi-day replay</h3>
 * <p>Uses {@link LocalDate} for day boundary detection (handles year-end rollovers).
 * Only the most recent day's VWAP is displayed — old lines are cleared on reset.</p>
 *
 * <h3>Time source</h3>
 * <p>All time decisions use {@code currentTimestampNs} — the most recent data/replay timestamp.
 * This is critical for replay mode where system clock time is irrelevant.</p>
 *
 * <h3>Threading</h3>
 * <p>{@code currentTimestampNs} is volatile for cross-thread visibility between
 * {@code setTimestamp()} (Bookmap data thread) and {@code backfillFromHistory()}
 * (Bookmap async callback thread).</p>
 */
public class VwapTracker implements IndicatorConfig.ChangeListener {

    // Eastern Time zone — session boundary checks happen in ET
    private static final ZoneId ET = ZoneId.of("America/New_York");

    // Trading session start — VWAP resets at 4:00 AM ET each day
    // This aligns with premarket open for US equities
    private static final LocalTime SESSION_START = LocalTime.of(4, 0);

    /** Where we store and update the drawn VWAP price line. */
    private final PriceLineStore store;

    /** User-facing toggle for enabling/disabling the VWAP indicator. */
    private final IndicatorConfig config;

    /**
     * Per-instrument VWAP state. Each instrument has its own VWAP
     * because different symbols have independent price/volume profiles.
     */
    private final Map<String, VwapState> states = new ConcurrentHashMap<>();

    public VwapTracker(PriceLineStore store, IndicatorConfig config) {
        this.store = store;
        this.config = config;
        // Listen for config changes so we can remove VWAP line when indicator is disabled
        config.addChangeListener(this);
    }

    /**
     * The most recent data/replay time in epoch nanoseconds.
     *
     * Updated on every tick by the plugin's TimeListener. Used to:
     *   1. Determine which day we're on for daily reset logic
     *   2. Determine which day's trades to query during backfill
     *
     * Volatile for cross-thread visibility (data thread vs async backfill callback).
     */
    private volatile long currentTimestampNs;

    /**
     * Called by the plugin's TimeListener on every tick to keep the tracker
     * in sync with data/replay time.
     *
     * @param timestampNs current data/replay time in epoch nanoseconds
     */
    public void setTimestamp(long timestampNs) {
        this.currentTimestampNs = timestampNs;
    }

    /**
     * Called on each trade event. Accumulates price*volume and volume into the running
     * VWAP calculation, then updates the price line on the chart.
     *
     * <p>VWAP is active for the entire trading day starting at 4:00 AM ET.
     * Unlike premarket high/low which stops at 9:30 AM, VWAP continues through
     * regular trading hours until the next day's 4:00 AM reset.</p>
     *
     * <p>Day boundary detection works the same as PremarketTracker: when the date changes
     * and we're past 4:00 AM ET, cumulative totals are cleared and the old VWAP line
     * is removed from the chart.</p>
     *
     * @param instrumentAlias instrument identifier (e.g. "AAPL", "ESZ4")
     * @param priceTick       price in tick units (used for canvas Y-coordinate positioning)
     * @param realPrice       price in real/display units (e.g. dollars)
     * @param size            trade volume (number of shares/contracts in this trade)
     */
    public void onTrade(String instrumentAlias, double priceTick, double realPrice, int size) {
        // Skip if indicator is disabled or we haven't received any timestamp yet
        if (!config.isEnabled(IndicatorConfig.VWAP)) return;
        if (currentTimestampNs == 0) return;

        // Convert the nanosecond epoch timestamp to Eastern Time for session boundary checks
        ZonedDateTime now = Instant.ofEpochSecond(0, currentTimestampNs).atZone(ET);
        LocalTime timeET = now.toLocalTime();
        LocalDate today = now.toLocalDate();

        VwapState state = states.computeIfAbsent(instrumentAlias, k -> new VwapState());

        // --- Day boundary reset ---
        // Reset VWAP at 4:00 AM ET on a new calendar day.
        // Trades between midnight and 4:00 AM don't trigger a reset — the previous day's
        // VWAP line remains visible until the new session begins.
        if ((state.lastSessionDate == null || !state.lastSessionDate.equals(today))
                && !timeET.isBefore(SESSION_START)) {
            state.reset();
            state.lastSessionDate = today;
            // Clear the previous day's VWAP line from the chart
            store.removeByType(instrumentAlias, PriceLine.LineType.VWAP);
            PluginLog.info("[VwapTracker] Reset for new session day " + today + " on " + instrumentAlias);
        }

        // Only accumulate trades after session start (4:00 AM ET)
        // Overnight trades (midnight to 4 AM) are excluded from VWAP calculation
        if (timeET.isBefore(SESSION_START)) return;

        // Skip zero-size trades (shouldn't happen but defensive check)
        if (size <= 0) return;

        // --- Accumulate into VWAP ---
        // VWAP = cumulative(price * volume) / cumulative(volume)
        // We use realPrice (dollars) for the numerator so the VWAP result is in dollars
        state.cumulativePriceVolume += realPrice * size;
        state.cumulativeVolume += size;

        // Calculate the current VWAP value
        double vwapPrice = state.cumulativePriceVolume / state.cumulativeVolume;

        // Convert VWAP price back to tick units for canvas positioning
        // priceTick = price / pips, but we don't have pips here directly.
        // Instead, we derive it from the current trade: pips = realPrice / priceTick
        // This is safe because pips is constant for an instrument.
        double pips = realPrice / priceTick;
        double vwapTick = vwapPrice / pips;

        // Store pips for potential future use (e.g. backfill)
        state.pips = pips;

        // Create/replace the VWAP line in the store (triggers chart repaint)
        PriceLine vwapLine = new PriceLine(instrumentAlias, PriceLine.LineType.VWAP,
                vwapTick, vwapPrice);
        store.replaceByType(instrumentAlias, PriceLine.LineType.VWAP, vwapLine);
    }

    /**
     * Backfill VWAP from historical trade data.
     *
     * <p>Called once during plugin initialization via Bookmap's async
     * {@code Layer1ApiDataInterfaceRequestMessage} callback. This handles the case where the
     * user attaches the plugin after trading has already started — without backfill, the VWAP
     * would only reflect trades from the attachment point forward.</p>
     *
     * <p>The backfill scans all historical trades for the current day (from 4:00 AM ET onward)
     * and computes the cumulative VWAP. Volume is reconstructed from the
     * {@link TradeAggregationEvent} maps: for each price level, total volume =
     * sum(tradeSize * tradeCount) across all entries in the inner map.</p>
     *
     * <p>Uses the same race condition guard as PremarketTracker: if streaming has already
     * advanced to a newer day, the backfill result is discarded.</p>
     *
     * @param dataInterface   Bookmap's historical data query interface
     * @param instrumentAlias instrument identifier
     * @param pips            price multiplier to convert tick values to real prices
     * @param initTimeNs      initial data time (fallback if no streaming timestamps received yet)
     */
    public void backfillFromHistory(DataStructureInterface dataInterface, String instrumentAlias,
                                     double pips, long initTimeNs) {
        if (!config.isEnabled(IndicatorConfig.VWAP)) return;

        // Determine the effective "now" — prefer the most recent streaming timestamp
        long effectiveTimeNs = currentTimestampNs > 0 ? currentTimestampNs : initTimeNs;

        // Calculate today's session window in Eastern Time
        ZonedDateTime now = Instant.ofEpochSecond(0, effectiveTimeNs).atZone(ET);
        LocalDate today = now.toLocalDate();

        // Query from 4:00 AM ET today to the current time
        long sessionStartNs = today.atTime(SESSION_START).atZone(ET).toInstant().toEpochMilli() * 1_000_000L;
        long queryEndNs = effectiveTimeNs;

        // Only query if we're past 4:00 AM ET
        if (queryEndNs <= sessionStartNs) return;

        try {
            // Query Bookmap's historical data store for all trades since session start.
            // We use multiple intervals (e.g. 100) to get finer granularity, though for VWAP
            // the total cumulative result is what matters.
            List<TreeResponseInterval> intervals = dataInterface.get(
                    sessionStartNs, queryEndNs, 100, instrumentAlias,
                    new StandardEvents[]{StandardEvents.TRADE});

            if (intervals == null || intervals.isEmpty()) return;

            // Accumulate price*volume and volume across all historical trades
            double cumulativePV = 0;
            long cumulativeVol = 0;

            for (TreeResponseInterval interval : intervals) {
                if (interval.events == null) continue;
                for (Object eventObj : interval.events.values()) {
                    if (!(eventObj instanceof TradeAggregationEvent)) continue;
                    TradeAggregationEvent trade = (TradeAggregationEvent) eventObj;

                    // Scan both bid and ask aggressor maps to capture all traded volume.
                    // Each map is Map<Double, Map<Integer, Integer>>:
                    //   outer key = price in ticks
                    //   inner key = individual trade size
                    //   inner value = count of trades at that size
                    // Total volume at a price = sum(tradeSize * count) for all inner entries.
                    for (Map<Double, ?> priceMap : new Map[]{trade.bidAggressorMap, trade.askAggressorMap}) {
                        if (priceMap == null) continue;
                        for (Map.Entry<Double, ?> priceEntry : priceMap.entrySet()) {
                            Double priceTick = priceEntry.getKey();
                            if (priceTick == null) continue;
                            double realPrice = priceTick * pips;

                            // The value is Map<Integer, Integer> (tradeSize -> count)
                            @SuppressWarnings("unchecked")
                            Map<Integer, Integer> sizeCountMap = (Map<Integer, Integer>) priceEntry.getValue();
                            if (sizeCountMap == null) continue;

                            for (Map.Entry<Integer, Integer> sizeEntry : sizeCountMap.entrySet()) {
                                int tradeSize = sizeEntry.getKey();
                                int tradeCount = sizeEntry.getValue();
                                long volume = (long) tradeSize * tradeCount;
                                cumulativePV += realPrice * volume;
                                cumulativeVol += volume;
                            }
                        }
                    }
                }
            }

            if (cumulativeVol > 0) {
                VwapState state = states.computeIfAbsent(instrumentAlias, k -> new VwapState());

                // Race condition guard: if streaming has already moved to a newer day,
                // discard this backfill to avoid overwriting current data
                if (state.lastSessionDate != null && state.lastSessionDate.isAfter(today)) {
                    PluginLog.info("[VwapTracker] Backfill skipped for " + instrumentAlias
                            + ": streaming already on " + state.lastSessionDate + ", backfill was for " + today);
                    return;
                }

                // Apply backfill results to state
                state.lastSessionDate = today;
                state.cumulativePriceVolume = cumulativePV;
                state.cumulativeVolume = cumulativeVol;
                state.pips = pips;

                // Calculate VWAP and draw the line
                double vwapPrice = cumulativePV / cumulativeVol;
                double vwapTick = vwapPrice / pips;

                PriceLine vwapLine = new PriceLine(instrumentAlias, PriceLine.LineType.VWAP,
                        vwapTick, vwapPrice);
                store.replaceByType(instrumentAlias, PriceLine.LineType.VWAP, vwapLine);

                PluginLog.info("[VwapTracker] Backfilled " + instrumentAlias
                        + ": VWAP=" + String.format("%.4f", vwapPrice)
                        + " (vol=" + cumulativeVol + ")"
                        + " for " + today + " (effectiveTime=" + now + ")");
            }
        } catch (Exception e) {
            PluginLog.error("[VwapTracker] Backfill failed for " + instrumentAlias + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Remove tracking state for an instrument (called when plugin stops for that symbol). */
    public void unregister(String instrumentAlias) {
        states.remove(instrumentAlias);
    }

    /**
     * Called when the user toggles an indicator on/off in the settings panel.
     * If VWAP is disabled, immediately remove all VWAP lines from the chart.
     */
    @Override
    public void onIndicatorConfigChanged(String indicatorKey, boolean enabled) {
        if (IndicatorConfig.VWAP.equals(indicatorKey) && !enabled) {
            for (String alias : states.keySet()) {
                store.removeByType(alias, PriceLine.LineType.VWAP);
            }
        }
    }

    /** Clean up when the last plugin instance shuts down. */
    public void shutdown() {
        config.removeChangeListener(this);
        states.clear();
    }

    /**
     * Per-instrument VWAP tracking state.
     *
     * Accumulates cumulative price*volume and cumulative volume across all trades
     * in the current session. VWAP = cumulativePriceVolume / cumulativeVolume.
     */
    private static class VwapState {
        /** Running sum of (realPrice * volume) for all trades in this session. */
        double cumulativePriceVolume = 0;

        /** Running sum of volume for all trades in this session. */
        long cumulativeVolume = 0;

        /** Price multiplier (ticks to real price). Cached from trade data for use in backfill. */
        double pips = 0;

        /** Which calendar date this VWAP state belongs to. Used for daily reset detection. */
        LocalDate lastSessionDate = null;

        /** Reset all cumulative values for a new trading session. */
        void reset() {
            cumulativePriceVolume = 0;
            cumulativeVolume = 0;
        }
    }
}
