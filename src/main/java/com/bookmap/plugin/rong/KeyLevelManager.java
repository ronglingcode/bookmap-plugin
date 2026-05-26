package com.bookmap.plugin.rong;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.bookmap.plugin.rong.pricelines.PriceLine;
import com.bookmap.plugin.rong.pricelines.PriceLineStore;

/**
 * Bridges key-level updates received over WebSocket to rendered price lines.
 */
public class KeyLevelManager implements SignalWebSocketServer.KeyLevelConfigListener {

    private final PriceLineStore store;
    private final Map<String, Double> instrumentPips = new ConcurrentHashMap<>();
    private final Map<String, List<KeyLevelDefinition>> levelsByInstrument = new ConcurrentHashMap<>();

    public KeyLevelManager(PriceLineStore store) {
        this.store = store;
    }

    /**
     * Called from the plugin's {@code initialize()} after InstrumentInfo is available.
     */
    public void onInstrumentInitialized(String instrumentAlias, double pips) {
        instrumentPips.put(instrumentAlias, pips);
        redrawInstrument(instrumentAlias);
    }

    /**
     * Called when the plugin stops for an instrument.
     * Removes all KEY_LEVEL lines for that instrument from the chart.
     */
    public void onInstrumentStopped(String instrumentAlias) {
        instrumentPips.remove(instrumentAlias);
        store.removeByType(instrumentAlias, PriceLine.LineType.KEY_LEVEL);
    }

    @Override
    public void onKeyLevelsChanged(String symbol, List<KeyLevelDefinition> levels) {
        String instrumentAlias = SymbolUtils.cleanSymbol(symbol);
        levelsByInstrument.put(instrumentAlias, Collections.unmodifiableList(new ArrayList<>(levels)));
        redrawInstrument(instrumentAlias);
    }

    private void redrawInstrument(String instrumentAlias) {
        Double pips = instrumentPips.get(instrumentAlias);
        if (pips == null) {
            return;
        }

        store.removeByType(instrumentAlias, PriceLine.LineType.KEY_LEVEL);
        List<KeyLevelDefinition> levels =
                levelsByInstrument.getOrDefault(instrumentAlias, Collections.emptyList());
        for (KeyLevelDefinition def : levels) {
            addLevelToStore(def, pips);
        }

        PluginLog.info("[KeyLevelManager] Drew " + levels.size()
                + " websocket key level(s) for " + instrumentAlias);
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
        instrumentPips.clear();
        levelsByInstrument.clear();
    }
}
