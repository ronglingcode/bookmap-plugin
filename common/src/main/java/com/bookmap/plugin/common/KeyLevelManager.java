package com.bookmap.plugin.common;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridge between {@link KeyLevelConfig} (raw key level definitions with real prices) and
 * {@link PriceLineStore} (rendered price lines with tick coordinates on the chart).
 *
 * <h3>Why this class exists</h3>
 * <p>Key level definitions store prices in real units (e.g., $180.00 for NVDA), but Bookmap's
 * canvas coordinate system needs prices in tick units. The tick conversion requires
 * {@code InstrumentInfo.pips}, which is only available after the plugin's {@code initialize()}
 * method is called for each instrument. This manager handles the deferred conversion:</p>
 *
 * <ol>
 *   <li>When {@link #onInstrumentInitialized} is called, it looks up all key level definitions
 *       for that instrument, converts prices to ticks using {@code pips}, and adds the
 *       resulting {@link PriceLine} objects to the store.</li>
 *   <li>It listens for runtime additions via {@link KeyLevelConfig.ChangeListener}. When the
 *       user adds a new key level through the settings panel for an already-initialized
 *       instrument, the manager immediately converts and draws it.</li>
 * </ol>
 *
 * <h3>Thread safety</h3>
 * <p>The {@code instrumentPips} map uses {@link ConcurrentHashMap} since initialization
 * happens on Bookmap's data thread while settings panel interactions happen on the EDT.</p>
 */
public class KeyLevelManager implements KeyLevelConfig.ChangeListener {

    private final KeyLevelConfig config;
    private final PriceLineStore store;

    /**
     * Tracks which instruments have been initialized and their pips multipliers.
     * Needed so that when a session level is added via the settings panel, we can
     * immediately convert the real price to ticks for any already-active instrument.
     */
    private final Map<String, Double> instrumentPips = new ConcurrentHashMap<>();

    public KeyLevelManager(KeyLevelConfig config, PriceLineStore store) {
        this.config = config;
        this.store = store;
        config.addChangeListener(this);
    }

    /**
     * Called from the plugin's {@code initialize()} after InstrumentInfo is available.
     *
     * <p>Looks up all key level definitions matching this instrument alias (from both
     * the config file and any session levels added before this instrument was initialized),
     * converts their real prices to tick coordinates, and adds them to the PriceLineStore
     * so they appear on the chart.</p>
     *
     * @param instrumentAlias Bookmap instrument alias (e.g., "NVDA")
     * @param pips            price multiplier from InstrumentInfo (realPrice = tickPrice * pips)
     */
    public void onInstrumentInitialized(String instrumentAlias, double pips) {
        instrumentPips.put(instrumentAlias, pips);

        // Materialize all key level definitions for this instrument into drawn PriceLines
        List<KeyLevelDefinition> levels = config.getLevelsForInstrument(instrumentAlias);
        for (KeyLevelDefinition def : levels) {
            addLevelToStore(def, pips);
        }

        if (!levels.isEmpty()) {
            System.out.println("[KeyLevelManager] Drew " + levels.size()
                    + " key level(s) for " + instrumentAlias);
        }
    }

    /**
     * Called when the plugin stops for an instrument.
     * Removes all KEY_LEVEL lines for that instrument from the chart.
     */
    public void onInstrumentStopped(String instrumentAlias) {
        instrumentPips.remove(instrumentAlias);
        store.removeByType(instrumentAlias, PriceLine.LineType.KEY_LEVEL);
    }

    /**
     * Called when key levels are added/removed via the settings panel at runtime.
     *
     * <p>Rebuilds all KEY_LEVEL lines for all active instruments. This is simpler than
     * tracking individual additions/removals and is fast enough since key levels are
     * typically few (< 50 total).</p>
     */
    @Override
    public void onKeyLevelsChanged() {
        // Rebuild key level lines for all currently active instruments
        for (Map.Entry<String, Double> entry : instrumentPips.entrySet()) {
            String alias = entry.getKey();
            double pips = entry.getValue();

            // Remove existing KEY_LEVEL lines for this instrument
            store.removeByType(alias, PriceLine.LineType.KEY_LEVEL);

            // Re-add all current definitions
            List<KeyLevelDefinition> levels = config.getLevelsForInstrument(alias);
            for (KeyLevelDefinition def : levels) {
                addLevelToStore(def, pips);
            }
        }
    }

    /**
     * Converts a KeyLevelDefinition to a PriceLine and adds it to the store.
     *
     * @param def  the key level definition with real price
     * @param pips the instrument's price multiplier (realPrice = tickPrice * pips)
     */
    private void addLevelToStore(KeyLevelDefinition def, double pips) {
        // Convert real price to tick units: tickPrice = realPrice / pips
        double priceInTicks = def.getPrice() / pips;

        // Create a PriceLine with the custom label (if provided)
        PriceLine line = new PriceLine(
                def.getInstrument(),
                PriceLine.LineType.KEY_LEVEL,
                priceInTicks,
                def.getPrice(),
                def.getLabel()  // custom label, or null for default "Key Level"
        );
        store.addLine(line);
    }

    /** Clean up when the last plugin instance shuts down. */
    public void shutdown() {
        config.removeChangeListener(this);
        instrumentPips.clear();
    }
}
