package com.bookmap.plugin.rong;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.bookmap.plugin.rong.pricelines.PriceZone;
import com.bookmap.plugin.rong.pricelines.PriceZoneStore;

/**
 * Bridges key-zone updates received over WebSocket to rendered price zones.
 */
public class KeyZoneManager implements SignalWebSocketServer.KeyZoneConfigListener {

    private static final Map<String, Color> NAMED_COLORS = new ConcurrentHashMap<>();
    static {
        NAMED_COLORS.put("black", Color.BLACK);
        NAMED_COLORS.put("blue", Color.BLUE);
        NAMED_COLORS.put("cyan", Color.CYAN);
        NAMED_COLORS.put("gray", Color.GRAY);
        NAMED_COLORS.put("grey", Color.GRAY);
        NAMED_COLORS.put("green", Color.GREEN);
        NAMED_COLORS.put("orange", Color.ORANGE);
        NAMED_COLORS.put("pink", Color.PINK);
        NAMED_COLORS.put("red", Color.RED);
        NAMED_COLORS.put("white", Color.WHITE);
        NAMED_COLORS.put("yellow", Color.YELLOW);
    }

    private final PriceZoneStore store;
    private final Map<String, Double> instrumentPips = new ConcurrentHashMap<>();
    private final Map<String, List<KeyZoneDefinition>> zonesByInstrument = new ConcurrentHashMap<>();

    public KeyZoneManager(PriceZoneStore store) {
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
    public void onKeyZonesChanged(String symbol, List<KeyZoneDefinition> zones) {
        String instrumentAlias = SymbolUtils.cleanSymbol(symbol);
        zonesByInstrument.put(instrumentAlias, Collections.unmodifiableList(new ArrayList<>(zones)));
        redrawInstrument(instrumentAlias);
    }

    private void redrawInstrument(String instrumentAlias) {
        Double pips = instrumentPips.get(instrumentAlias);
        if (pips == null || !Double.isFinite(pips) || pips <= 0) {
            return;
        }

        List<KeyZoneDefinition> definitions =
                zonesByInstrument.getOrDefault(instrumentAlias, Collections.emptyList());
        List<PriceZone> zones = new ArrayList<>();
        for (KeyZoneDefinition definition : definitions) {
            PriceZone zone = toPriceZone(definition, pips);
            if (zone != null) {
                zones.add(zone);
            }
        }

        store.replaceAll(instrumentAlias, zones);
        PluginLog.info("[KeyZoneManager] Drew " + zones.size()
                + " websocket key zone(s) for " + instrumentAlias);
    }

    private PriceZone toPriceZone(KeyZoneDefinition definition, double pips) {
        if (!isValidZone(definition)) {
            return null;
        }
        return new PriceZone(
                definition.getInstrument(),
                definition.getLow() / pips,
                definition.getHigh() / pips,
                definition.getLow(),
                definition.getHigh(),
                definition.getLabel(),
                parseColor(definition.getColor()));
    }

    private boolean isValidZone(KeyZoneDefinition definition) {
        return definition != null
                && Double.isFinite(definition.getLow())
                && Double.isFinite(definition.getHigh())
                && definition.getLow() > 0
                && definition.getHigh() > definition.getLow();
    }

    private Color parseColor(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.US);
        if (normalized.isEmpty()) {
            return null;
        }
        Color namedColor = NAMED_COLORS.get(normalized);
        if (namedColor != null) {
            return namedColor;
        }
        try {
            if (normalized.startsWith("#") && normalized.length() == 4) {
                String r = normalized.substring(1, 2);
                String g = normalized.substring(2, 3);
                String b = normalized.substring(3, 4);
                return Color.decode("#" + r + r + g + g + b + b);
            }
            return Color.decode(normalized);
        } catch (NumberFormatException e) {
            PluginLog.error("[KeyZoneManager] Ignoring invalid zone color '" + value + "'");
            return null;
        }
    }

    public void shutdown() {
        instrumentPips.clear();
        zonesByInstrument.clear();
    }
}
