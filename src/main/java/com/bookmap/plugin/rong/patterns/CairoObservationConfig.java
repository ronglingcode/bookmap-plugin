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
    public final boolean enabled, observerOnly;
    private final Set<String> symbols = new HashSet<>();
    private final Set<String> detectors = new HashSet<>();
    public CairoObservationConfig(JsonObject value) {
        enabled = value.has("enabled") && value.get("enabled").getAsBoolean();
        observerOnly = enabled && value.has("observerOnly") && value.get("observerOnly").getAsBoolean();
        if (value.has("symbols")) value.getAsJsonArray("symbols").forEach(item -> symbols.add(SymbolUtils.cleanSymbol(item.getAsString()).toUpperCase(java.util.Locale.ROOT)));
        if (value.has("detectors")) value.getAsJsonArray("detectors").forEach(item -> detectors.add(item.getAsString()));
    }
    public boolean eligible(String symbol, PatternType type) { return enabled && symbols.contains(SymbolUtils.cleanSymbol(symbol).toUpperCase(java.util.Locale.ROOT)) && CairoObservationExport.allowed(type) && detectors.contains(type.name()); }
    public static CairoObservationConfig load() {
        try {
            Path path = Path.of(System.getProperty("user.home"), "bmtrader", "cairo-observation.json");
            if (Files.size(path) > 16_384) throw new IllegalArgumentException("Oversized observer config");
            return new CairoObservationConfig(JsonParser.parseString(Files.readString(path)).getAsJsonObject());
        } catch (Exception ignored) { return new CairoObservationConfig(new JsonObject()); }
    }
}
