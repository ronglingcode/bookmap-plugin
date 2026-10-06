package com.bookmap.plugin.rong.patterns;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.bookmap.plugin.rong.SymbolUtils;
/** Separate local observation preferences, read at attachment; never modifies execution settings. */
public final class CairoObservationConfig {
    public final boolean enabled, observerOnly, evidenceEnabled, captureEvidence;
    public final String sourceMode;
    private final Set<String> symbols = new HashSet<>();
    private final Set<String> evidenceSymbols = new HashSet<>();
    private final Set<String> detectors = new HashSet<>();
    public CairoObservationConfig(JsonObject value) {
        evidenceEnabled = !value.has("evidenceEnabled") || value.get("evidenceEnabled").getAsBoolean();
        captureEvidence = !value.has("captureEvidence") || value.get("captureEvidence").getAsBoolean();
        String mode = value.has("sourceMode") ? value.get("sourceMode").getAsString() : "unknown";
        sourceMode = java.util.Set.of("live", "replay", "unknown").contains(mode) ? mode : "unknown";
        enabled = value.has("enabled") && value.get("enabled").getAsBoolean();
        observerOnly = (enabled || evidenceEnabled) && value.has("observerOnly") && value.get("observerOnly").getAsBoolean();
        if (value.has("symbols")) value.getAsJsonArray("symbols").forEach(item -> symbols.add(SymbolUtils.cleanSymbol(item.getAsString()).toUpperCase(java.util.Locale.ROOT)));
        if (value.has("evidenceSymbols")) value.getAsJsonArray("evidenceSymbols").forEach(item -> evidenceSymbols.add(SymbolUtils.cleanSymbol(item.getAsString()).toUpperCase(java.util.Locale.ROOT)));
        if (value.has("detectors")) value.getAsJsonArray("detectors").forEach(item -> detectors.add(item.getAsString()));
    }
    public boolean recordEvidence(String symbol) { return evidenceEnabled && (evidenceSymbols.isEmpty() || evidenceSymbols.contains(SymbolUtils.cleanSymbol(symbol).toUpperCase(java.util.Locale.ROOT))); }
    public boolean eligible(String symbol, PatternType type) { return enabled && symbols.contains(SymbolUtils.cleanSymbol(symbol).toUpperCase(java.util.Locale.ROOT)) && CairoObservationExport.allowed(type) && detectors.contains(type.name()); }
    public static CairoObservationConfig load() {
        try {
            Path path = Path.of(System.getProperty("bmtrader.observationConfig", Path.of(System.getProperty("user.home"), "bmtrader", "cairo-observation.json").toString()));
            if (Files.size(path) > 16_384) throw new IllegalArgumentException("Oversized observer config");
            return new CairoObservationConfig(JsonParser.parseString(Files.readString(path)).getAsJsonObject());
        } catch (Exception ignored) { return new CairoObservationConfig(new JsonObject()); }
    }
}
