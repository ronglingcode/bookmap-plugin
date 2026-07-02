package com.bookmap.plugin.rong;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.bookmap.plugin.rong.pricelines.PriceLine;
import com.bookmap.plugin.rong.pricelines.PriceLineStore;

/**
 * Renders market levels supplied by the websocket client.
 */
public class MarketLevelManager implements SignalWebSocketServer.MarketLevelConfigListener,
        IndicatorConfig.ChangeListener {

    private static final Map<String, PriceLine.LineType> CAM_LEVEL_TYPES = new LinkedHashMap<>();
    static {
        CAM_LEVEL_TYPES.put("R1", PriceLine.LineType.CAM_R1);
        CAM_LEVEL_TYPES.put("R2", PriceLine.LineType.CAM_R2);
        CAM_LEVEL_TYPES.put("R3", PriceLine.LineType.CAM_R3);
        CAM_LEVEL_TYPES.put("R4", PriceLine.LineType.CAM_R4);
        CAM_LEVEL_TYPES.put("R5", PriceLine.LineType.CAM_R5);
        CAM_LEVEL_TYPES.put("R6", PriceLine.LineType.CAM_R6);
        CAM_LEVEL_TYPES.put("S1", PriceLine.LineType.CAM_S1);
        CAM_LEVEL_TYPES.put("S2", PriceLine.LineType.CAM_S2);
        CAM_LEVEL_TYPES.put("S3", PriceLine.LineType.CAM_S3);
        CAM_LEVEL_TYPES.put("S4", PriceLine.LineType.CAM_S4);
        CAM_LEVEL_TYPES.put("S5", PriceLine.LineType.CAM_S5);
        CAM_LEVEL_TYPES.put("S6", PriceLine.LineType.CAM_S6);
    }

    private final PriceLineStore store;
    private final IndicatorConfig config;
    private final Map<String, Double> instrumentPips = new ConcurrentHashMap<>();
    private final Map<String, MarketLevelDefinition> levelsByInstrument = new ConcurrentHashMap<>();

    public MarketLevelManager(PriceLineStore store, IndicatorConfig config) {
        this.store = store;
        this.config = config;
        config.addChangeListener(this);
    }

    public void onInstrumentInitialized(String instrumentAlias, double pips) {
        String cleanInstrumentAlias = SymbolUtils.cleanSymbol(instrumentAlias);
        instrumentPips.put(cleanInstrumentAlias, pips);
        redrawInstrument(cleanInstrumentAlias);
    }

    public void onInstrumentStopped(String instrumentAlias) {
        String cleanInstrumentAlias = SymbolUtils.cleanSymbol(instrumentAlias);
        instrumentPips.remove(cleanInstrumentAlias);
        removeAllLines(cleanInstrumentAlias);
    }

    @Override
    public void onMarketLevelsChanged(String symbol, MarketLevelDefinition marketLevels) {
        String instrumentAlias = SymbolUtils.cleanSymbol(symbol);
        levelsByInstrument.put(instrumentAlias, marketLevels);
        redrawInstrument(instrumentAlias);
    }

    @Override
    public void onIndicatorConfigChanged(String indicatorKey, boolean enabled) {
        if (!IndicatorConfig.CAM_PIVOTS.equals(indicatorKey)
                && !IndicatorConfig.PREMARKET_HIGH_LOW.equals(indicatorKey)) {
            return;
        }
        for (String instrumentAlias : instrumentPips.keySet()) {
            redrawInstrument(instrumentAlias);
        }
    }

    public void shutdown() {
        config.removeChangeListener(this);
        for (String instrumentAlias : instrumentPips.keySet()) {
            removeAllLines(instrumentAlias);
        }
        instrumentPips.clear();
        levelsByInstrument.clear();
    }

    private void redrawInstrument(String instrumentAlias) {
        Double pips = instrumentPips.get(instrumentAlias);
        if (pips == null || !Double.isFinite(pips) || pips <= 0) {
            return;
        }

        removeAllLines(instrumentAlias);

        MarketLevelDefinition levels = levelsByInstrument.get(instrumentAlias);
        if (levels == null) {
            return;
        }

        int linesDrawn = 0;
        linesDrawn += drawCamPivots(instrumentAlias, pips, levels.getCamPivots());
        linesDrawn += drawPreviousDayLevels(instrumentAlias, pips, levels);
        linesDrawn += drawPremarketLevels(instrumentAlias, pips, levels);

        PluginLog.info("[MarketLevelManager] Drew " + linesDrawn
                + " websocket market level(s) for " + instrumentAlias);
    }

    private int drawCamPivots(String instrumentAlias, double pips, Map<String, Double> camPivots) {
        if (!config.isEnabled(IndicatorConfig.CAM_PIVOTS)) {
            return 0;
        }

        int count = 0;
        for (Map.Entry<String, PriceLine.LineType> entry : CAM_LEVEL_TYPES.entrySet()) {
            String levelName = entry.getKey();
            double price = getPrice(camPivots, levelName);
            if (!isValidPrice(price)) {
                continue;
            }
            store.replaceByType(instrumentAlias, entry.getValue(),
                    new PriceLine(instrumentAlias, entry.getValue(), price / pips, price, levelName));
            count++;
        }
        return count;
    }

    private int drawPreviousDayLevels(String instrumentAlias, double pips, MarketLevelDefinition levels) {
        int count = 0;
        count += drawLineIfValid(
                instrumentAlias,
                pips,
                PriceLine.LineType.PREVIOUS_DAY_HIGH,
                levels.getPreviousDayHigh(),
                "y-high");
        count += drawLineIfValid(
                instrumentAlias,
                pips,
                PriceLine.LineType.PREVIOUS_DAY_LOW,
                levels.getPreviousDayLow(),
                "y-low");
        return count;
    }

    private int drawPremarketLevels(String instrumentAlias, double pips, MarketLevelDefinition levels) {
        if (!config.isEnabled(IndicatorConfig.PREMARKET_HIGH_LOW)) {
            return 0;
        }

        int count = 0;
        count += drawLineIfValid(
                instrumentAlias,
                pips,
                PriceLine.LineType.PREMARKET_HIGH,
                levels.getPremarketHigh(),
                null);
        count += drawLineIfValid(
                instrumentAlias,
                pips,
                PriceLine.LineType.PREMARKET_LOW,
                levels.getPremarketLow(),
                null);
        return count;
    }

    private int drawLineIfValid(
            String instrumentAlias,
            double pips,
            PriceLine.LineType type,
            double price,
            String label) {
        if (!isValidPrice(price)) {
            return 0;
        }
        store.replaceByType(instrumentAlias, type,
                new PriceLine(instrumentAlias, type, price / pips, price, label));
        return 1;
    }

    private double getPrice(Map<String, Double> prices, String key) {
        if (prices == null) {
            return Double.NaN;
        }
        Double price = prices.get(key);
        return price == null ? Double.NaN : price;
    }

    private boolean isValidPrice(double price) {
        return Double.isFinite(price) && price > 0;
    }

    private void removeAllLines(String instrumentAlias) {
        store.removeByTypes(instrumentAlias, getManagedLineTypes());
    }

    private List<PriceLine.LineType> getManagedLineTypes() {
        List<PriceLine.LineType> types = new ArrayList<>(CAM_LEVEL_TYPES.values());
        types.add(PriceLine.LineType.PREVIOUS_DAY_HIGH);
        types.add(PriceLine.LineType.PREVIOUS_DAY_LOW);
        types.add(PriceLine.LineType.PREMARKET_HIGH);
        types.add(PriceLine.LineType.PREMARKET_LOW);
        return types;
    }
}
