package com.bookmap.plugin.rong.executions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.bookmap.plugin.rong.AccountExecutionDefinition;
import com.bookmap.plugin.rong.AccountStateDefinition;
import com.bookmap.plugin.rong.PluginLog;
import com.bookmap.plugin.rong.SignalWebSocketServer;
import com.bookmap.plugin.rong.SymbolUtils;

/**
 * Bridges ViteApp account execution snapshots to Bookmap fill markers.
 */
public class FilledExecutionManager implements SignalWebSocketServer.AccountStateListener {

    private final FilledExecutionStore store;
    private final Map<String, Double> instrumentPips = new ConcurrentHashMap<>();
    private final Map<String, AccountStateDefinition> statesByInstrument = new ConcurrentHashMap<>();

    public FilledExecutionManager(FilledExecutionStore store) {
        this.store = store;
    }

    public void onInstrumentInitialized(String instrumentAlias, double pips) {
        String cleanInstrumentAlias = SymbolUtils.cleanSymbol(instrumentAlias);
        instrumentPips.put(cleanInstrumentAlias, pips);
        redrawInstrument(cleanInstrumentAlias);
    }

    public void onInstrumentStopped(String instrumentAlias) {
        String cleanInstrumentAlias = SymbolUtils.cleanSymbol(instrumentAlias);
        instrumentPips.remove(cleanInstrumentAlias);
        store.clearAll(cleanInstrumentAlias);
    }

    @Override
    public void onAccountStateChanged(AccountStateDefinition state) {
        if (state == null || state.getSymbol().isEmpty()) {
            return;
        }
        String instrumentAlias = SymbolUtils.cleanSymbol(state.getSymbol());
        statesByInstrument.put(instrumentAlias, state);
        redrawInstrument(instrumentAlias);
    }

    private void redrawInstrument(String instrumentAlias) {
        Double pips = instrumentPips.get(instrumentAlias);
        if (pips == null || pips <= 0 || !Double.isFinite(pips)) {
            return;
        }

        AccountStateDefinition state = statesByInstrument.get(instrumentAlias);
        if (state == null || state.getExecutions().isEmpty()) {
            store.clearAll(instrumentAlias);
            return;
        }

        List<FilledExecutionMarker> markers = new ArrayList<>();
        for (AccountExecutionDefinition execution : state.getExecutions()) {
            if (execution == null || execution.getPrice() <= 0 || execution.getTimeNs() <= 0) {
                continue;
            }
            markers.add(new FilledExecutionMarker(
                    instrumentAlias,
                    execution.getPrice() / pips,
                    execution.getPrice(),
                    execution.getQuantity(),
                    execution.isBuy(),
                    execution.isPositionEffectIsOpen(),
                    execution.getTimeNs()));
        }

        store.replaceAll(instrumentAlias, markers);
        PluginLog.info("[FilledExecution] Drew " + markers.size() + " fill marker(s) for " + instrumentAlias);
    }

    public void shutdown() {
        instrumentPips.clear();
        statesByInstrument.clear();
    }
}
