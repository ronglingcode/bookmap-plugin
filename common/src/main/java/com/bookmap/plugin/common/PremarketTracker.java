package com.bookmap.plugin.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks premarket high and low prices per instrument and draws them as price lines on the chart.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>The plugin's {@code TimeListener.onTimestamp()} calls {@link #setTimestamp(long)} on every
 *       tick to keep this tracker in sync with data/replay time (NOT system clock).</li>
 *   <li>The plugin's {@code TradeDataListener.onTrade()} calls {@link #onTrade} on every trade.
 *       If the trade falls within the premarket window (4:00-9:30 AM ET), the tracker updates
 *       the high/low and writes price lines to the {@link PriceLineStore}.</li>
 *   <li>On initialization, {@link #seedFromApi} receives premarket high/low from the
 *       EdgeDesk API, so the plugin has data even if attached mid-premarket.</li>
 * </ol>
 *
 * <h3>Multi-day replay handling</h3>
 * <p>When replaying multi-day feed data, the tracker automatically resets at the start of each
 * new trading day's premarket session. It uses {@link LocalDate} (not day-of-year int) to
 * detect day boundaries, which correctly handles year-end rollovers.</p>
 *
 * <h3>Time source</h3>
 * <p>All time decisions use {@code currentTimestampNs} — the most recent data/replay timestamp
 * received via {@link #setTimestamp(long)}. This is critical for replay mode where system clock
 * time has no relation to the data being replayed.</p>
 *
 * <h3>Threading</h3>
 * <p>{@code currentTimestampNs} is volatile because {@code setTimestamp()} and
 * {@code seedFromApi()} may be called from different threads. Per-instrument state
 * is stored in a ConcurrentHashMap.</p>
 *
 * <h3>Configuration</h3>
 * <p>Implements {@link IndicatorConfig.ChangeListener} so that when the user disables the
 * premarket indicator in the settings panel, all premarket lines are removed immediately.</p>
 */
public class PremarketTracker implements IndicatorConfig.ChangeListener {

    // Eastern Time zone — all premarket window comparisons happen in ET
    private static final ZoneId ET = ZoneId.of("America/New_York");

    // Premarket session boundaries in Eastern Time
    private static final LocalTime PREMARKET_START = LocalTime.of(4, 0);   // 4:00 AM ET
    private static final LocalTime PREMARKET_END = LocalTime.of(9, 30);    // 9:30 AM ET

    /** Where we store and update the drawn price lines (PM High / PM Low). */
    private final PriceLineStore store;

    /** User-facing toggle for enabling/disabling the premarket indicator. */
    private final IndicatorConfig config;

    /**
     * Per-instrument tracking state. Each instrument has its own premarket high/low
     * because different symbols have independent price ranges.
     */
    private final Map<String, PremarketState> states = new ConcurrentHashMap<>();

    public PremarketTracker(PriceLineStore store, IndicatorConfig config) {
        this.store = store;
        this.config = config;
        // Listen for config changes so we can remove lines when indicator is disabled
        config.addChangeListener(this);
    }

    /**
     * The most recent data/replay time in epoch nanoseconds.
     *
     * This is the global "last known time" — updated on every tick by the plugin's
     * TimeListener. It serves two purposes:
     *   1. In onTrade(): determines which day we're on and whether we're in premarket hours
     *   2. In backfillFromHistory(): determines which day's premarket to query from history
     *
     * Volatile because setTimestamp() is called from Bookmap's data thread, while
     * backfillFromHistory() runs on Bookmap's async callback thread.
     */
    private volatile long currentTimestampNs;

    /**
     * Called by the plugin's TimeListener on every tick to keep the tracker
     * in sync with data/replay time. This must be called BEFORE onTrade()
     * for each tick — Bookmap guarantees this ordering.
     *
     * @param timestampNs current data/replay time in epoch nanoseconds
     */
    public void setTimestamp(long timestampNs) {
        this.currentTimestampNs = timestampNs;
    }

    /**
     * Called on each trade event. Updates premarket high/low if the trade falls
     * within the premarket window (4:00-9:30 AM ET) based on the current data/replay time.
     *
     * <p>Day boundary detection: when the date changes (compared to the last tracked session),
     * the tracker resets its high/low state and removes old lines from the store. This ensures
     * that multi-day replay data correctly shows only the most recent day's premarket levels.</p>
     *
     * <p>The reset triggers on the first trade at or after 4:00 AM ET on a new day. Trades
     * between midnight and 4:00 AM don't trigger a reset (and also don't update premarket,
     * since they're outside the premarket window), so the previous day's lines remain visible
     * as reference levels until the new premarket session begins.</p>
     *
     * @param instrumentAlias instrument identifier (e.g. "AAPL", "ESZ4")
     * @param priceTick       price in tick units (used for canvas Y-coordinate positioning)
     * @param realPrice       price in real/display units (e.g. dollars, used for labels)
     */
    public void onTrade(String instrumentAlias, double priceTick, double realPrice) {
        // Skip if indicator is disabled or we haven't received any timestamp yet
        if (!config.isEnabled(IndicatorConfig.PREMARKET_HIGH_LOW)) return;
        if (currentTimestampNs == 0) return;

        // Convert the nanosecond epoch timestamp to Eastern Time for premarket window checks
        ZonedDateTime now = Instant.ofEpochSecond(0, currentTimestampNs).atZone(ET);
        LocalTime timeET = now.toLocalTime();
        LocalDate today = now.toLocalDate();

        PremarketState state = states.computeIfAbsent(instrumentAlias, k -> new PremarketState());

        // --- Day boundary reset ---
        // Check if we've moved to a new calendar day AND we're past 4:00 AM ET.
        // The 4:00 AM gate prevents premature reset for overnight trades (midnight-4AM).
        // Uses LocalDate instead of getDayOfYear() to correctly handle year-end rollovers.
        if ((state.lastSessionDate == null || !state.lastSessionDate.equals(today))
                && !timeET.isBefore(PREMARKET_START)) {
            state.reset();
            state.lastSessionDate = today;
            // Clear the previous day's premarket lines from the chart
            store.removeByType(instrumentAlias, PriceLine.LineType.PREMARKET_HIGH);
            store.removeByType(instrumentAlias, PriceLine.LineType.PREMARKET_LOW);
            PluginLog.info("[PremarketTracker] Reset for new session day " + today + " on " + instrumentAlias);
        }

        // Only track prices during the premarket window (4:00 AM - 9:30 AM ET)
        // Trades outside this window are ignored for premarket high/low purposes
        if (timeET.isBefore(PREMARKET_START) || !timeET.isBefore(PREMARKET_END)) return;

        // --- Update premarket high ---
        if (Double.isNaN(state.highPrice) || realPrice > state.highPrice) {
            state.highPrice = realPrice;
            state.highPriceTick = priceTick;
            // Create/replace the PM High line in the store (triggers chart repaint)
            PriceLine highLine = new PriceLine(instrumentAlias, PriceLine.LineType.PREMARKET_HIGH,
                    priceTick, realPrice);
            store.replaceByType(instrumentAlias, PriceLine.LineType.PREMARKET_HIGH, highLine);
        }

        // --- Update premarket low ---
        if (Double.isNaN(state.lowPrice) || realPrice < state.lowPrice) {
            state.lowPrice = realPrice;
            state.lowPriceTick = priceTick;
            // Create/replace the PM Low line in the store (triggers chart repaint)
            PriceLine lowLine = new PriceLine(instrumentAlias, PriceLine.LineType.PREMARKET_LOW,
                    priceTick, realPrice);
            store.replaceByType(instrumentAlias, PriceLine.LineType.PREMARKET_LOW, lowLine);
        }
    }

    /**
     * Seed premarket high/low from API data (called by IndicatorDataFetcher).
     *
     * <p>Sets initial premarket high/low before any streaming data arrives. If streaming
     * {@code onTrade()} has already set values for today, this call is ignored to avoid
     * overwriting more recent data.</p>
     *
     * @param instrumentAlias instrument identifier
     * @param pips            price multiplier (tick-to-real-price conversion)
     * @param high            premarket high price from API
     * @param low             premarket low price from API
     */
    public void seedFromApi(String instrumentAlias, double pips, double high, double low) {
        if (!config.isEnabled(IndicatorConfig.PREMARKET_HIGH_LOW)) return;

        PremarketState state = states.computeIfAbsent(instrumentAlias, k -> new PremarketState());

        // Don't overwrite if streaming data has already set values
        if (!Double.isNaN(state.highPrice)) {
            PluginLog.info("[PremarketTracker] API seed skipped for " + instrumentAlias
                    + ": streaming data already present");
            return;
        }

        double highTick = high / pips;
        double lowTick = low / pips;

        state.highPrice = high;
        state.highPriceTick = highTick;
        state.lowPrice = low;
        state.lowPriceTick = lowTick;

        PriceLine highLine = new PriceLine(instrumentAlias, PriceLine.LineType.PREMARKET_HIGH,
                highTick, high);
        store.replaceByType(instrumentAlias, PriceLine.LineType.PREMARKET_HIGH, highLine);

        PriceLine lowLine = new PriceLine(instrumentAlias, PriceLine.LineType.PREMARKET_LOW,
                lowTick, low);
        store.replaceByType(instrumentAlias, PriceLine.LineType.PREMARKET_LOW, lowLine);

        PluginLog.info("[PremarketTracker] Seeded from API " + instrumentAlias
                + ": PM High=" + high + ", PM Low=" + low);
    }

    /** Remove tracking state for an instrument (called when plugin stops for that symbol). */
    public void unregister(String instrumentAlias) {
        states.remove(instrumentAlias);
    }

    /**
     * Called when the user toggles an indicator on/off in the settings panel.
     * If premarket is disabled, immediately remove all premarket lines from the chart.
     */
    @Override
    public void onIndicatorConfigChanged(String indicatorKey, boolean enabled) {
        if (IndicatorConfig.PREMARKET_HIGH_LOW.equals(indicatorKey) && !enabled) {
            for (String alias : states.keySet()) {
                store.removeByType(alias, PriceLine.LineType.PREMARKET_HIGH);
                store.removeByType(alias, PriceLine.LineType.PREMARKET_LOW);
            }
        }
    }

    /** Clean up when the last plugin instance shuts down. */
    public void shutdown() {
        config.removeChangeListener(this);
        states.clear();
    }

    /**
     * Per-instrument premarket tracking state.
     * Tracks the current session's high/low prices and which day we're tracking.
     */
    private static class PremarketState {
        double highPrice = Double.NaN;       // Current premarket high in real price
        double lowPrice = Double.NaN;        // Current premarket low in real price
        double highPriceTick = Double.NaN;   // High price in tick units (for canvas positioning)
        double lowPriceTick = Double.NaN;    // Low price in tick units (for canvas positioning)
        LocalDate lastSessionDate = null;    // Which calendar date this state belongs to

        /** Reset high/low for a new premarket session (keeps lastSessionDate intact). */
        void reset() {
            highPrice = Double.NaN;
            lowPrice = Double.NaN;
            highPriceTick = Double.NaN;
            lowPriceTick = Double.NaN;
        }
    }
}
