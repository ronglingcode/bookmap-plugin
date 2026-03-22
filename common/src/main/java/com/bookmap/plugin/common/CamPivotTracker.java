package com.bookmap.plugin.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws Camarilla Pivot levels (R1–R6, S1–S6) as horizontal price lines on the chart.
 *
 * <p>Unlike PremarketTracker and VwapTracker, pivot levels are static for the entire trading day
 * (they're based on the previous day's OHLC). The levels are fetched once during initialization
 * via {@link IndicatorDataFetcher} and drawn immediately — no streaming trade data is needed.</p>
 *
 * <p>Implements {@link IndicatorConfig.ChangeListener} so that toggling the indicator off
 * in the settings panel removes all pivot lines immediately.</p>
 */
public class CamPivotTracker implements IndicatorConfig.ChangeListener {

    /** Mapping from pivot level name to the corresponding PriceLine.LineType. */
    private static final Map<String, PriceLine.LineType> LEVEL_TYPES = new LinkedHashMap<>();
    static {
        LEVEL_TYPES.put("R1", PriceLine.LineType.CAM_R1);
        LEVEL_TYPES.put("R2", PriceLine.LineType.CAM_R2);
        LEVEL_TYPES.put("R3", PriceLine.LineType.CAM_R3);
        LEVEL_TYPES.put("R4", PriceLine.LineType.CAM_R4);
        LEVEL_TYPES.put("R5", PriceLine.LineType.CAM_R5);
        LEVEL_TYPES.put("R6", PriceLine.LineType.CAM_R6);
        LEVEL_TYPES.put("S1", PriceLine.LineType.CAM_S1);
        LEVEL_TYPES.put("S2", PriceLine.LineType.CAM_S2);
        LEVEL_TYPES.put("S3", PriceLine.LineType.CAM_S3);
        LEVEL_TYPES.put("S4", PriceLine.LineType.CAM_S4);
        LEVEL_TYPES.put("S5", PriceLine.LineType.CAM_S5);
        LEVEL_TYPES.put("S6", PriceLine.LineType.CAM_S6);
    }

    private final PriceLineStore store;
    private final IndicatorConfig config;

    /** Tracks which instruments have pivot lines drawn. */
    private final Set<String> registeredInstruments = ConcurrentHashMap.newKeySet();

    public CamPivotTracker(PriceLineStore store, IndicatorConfig config) {
        this.store = store;
        this.config = config;
        config.addChangeListener(this);
    }

    /**
     * Draw all 12 Camarilla Pivot levels for the given instrument.
     *
     * @param instrumentAlias instrument identifier (e.g. "AAPL")
     * @param pips            price multiplier (tick-to-real-price conversion factor)
     * @param pivotLevels     map of level name ("R1"–"R6", "S1"–"S6") to price value
     */
    public void drawPivots(String instrumentAlias, double pips, Map<String, Double> pivotLevels) {
        if (!config.isEnabled(IndicatorConfig.CAM_PIVOTS)) return;

        registeredInstruments.add(instrumentAlias);

        for (Map.Entry<String, PriceLine.LineType> entry : LEVEL_TYPES.entrySet()) {
            String levelName = entry.getKey();
            PriceLine.LineType lineType = entry.getValue();
            Double price = pivotLevels.get(levelName);

            if (price == null) continue;

            double priceTick = price / pips;
            String label = levelName + " " + String.format("%.2f", price);
            PriceLine line = new PriceLine(instrumentAlias, lineType, priceTick, price, label);
            store.replaceByType(instrumentAlias, lineType, line);
        }

        PluginLog.info("[CamPivotTracker] Drew " + pivotLevels.size()
                + " pivot levels for " + instrumentAlias);
    }

    /** Remove all pivot lines for the given instrument. */
    public void unregister(String instrumentAlias) {
        registeredInstruments.remove(instrumentAlias);
        removeAllLines(instrumentAlias);
    }

    /**
     * Called when the user toggles an indicator on/off in the settings panel.
     * If cam pivots are disabled, remove all pivot lines from all instruments.
     */
    @Override
    public void onIndicatorConfigChanged(String indicatorKey, boolean enabled) {
        if (IndicatorConfig.CAM_PIVOTS.equals(indicatorKey) && !enabled) {
            for (String alias : registeredInstruments) {
                removeAllLines(alias);
            }
        }
    }

    /** Clean up when the last plugin instance shuts down. */
    public void shutdown() {
        config.removeChangeListener(this);
        for (String alias : registeredInstruments) {
            removeAllLines(alias);
        }
        registeredInstruments.clear();
    }

    private void removeAllLines(String instrumentAlias) {
        for (PriceLine.LineType lineType : LEVEL_TYPES.values()) {
            store.removeByType(instrumentAlias, lineType);
        }
    }
}
