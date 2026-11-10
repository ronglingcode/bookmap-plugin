package com.bookmap.plugin.rong.patterns;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.bookmap.plugin.rong.SymbolUtils;
import com.bookmap.plugin.rong.patterns.PatternSizeCategory;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;

/** Immutable rules snapshot. File settings never belong to the broker configuration. */
public final class PatternObservationConfig {
    public static final String CONFIG_PROPERTY = "bmtrader.patternObservationConfig";
    public static final int MAX_CONFIG_BYTES = 16384;
    public final boolean enabled, valid;
    public final String error, revision;
    public final Set<String> symbols;
    public final long observationFloorSize;
    public final int maxEpisodes, maxWallPhases, maxAttributionTrades;
    public final DetectorSettings detectors;

    private PatternObservationConfig(Builder b) {
        this(b, true, "");
    }
    private PatternObservationConfig(Builder b, boolean valid, String error) {
        this.valid = valid; this.error = error; enabled = valid && b.enabled;
        symbols = Collections.unmodifiableSet(new LinkedHashSet<>(b.symbols));
        observationFloorSize = PatternSizeCategory.MIN_TRACKED_SIZE;
        maxEpisodes = b.maxEpisodes; maxWallPhases = b.maxWallPhases; maxAttributionTrades = b.maxAttributionTrades;
        detectors = new DetectorSettings(b.detectors);
        revision = digest(toJson().toString());
    }

    public static PatternObservationConfig defaults() { return new PatternObservationConfig(new Builder()); }

    public static PatternObservationConfig load() {
        try {
            // Shipped code defaults are portable; machine-local files require explicit opt-in.
            String configured = System.getProperty(CONFIG_PROPERTY);
            return configured == null || configured.isBlank() ? defaults() : load(Path.of(configured));
        } catch (RuntimeException ex) { return invalid("cannot locate configuration: " + ex.getMessage()); }
    }

    public static PatternObservationConfig load(Path path) {
        try (InputStream stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(MAX_CONFIG_BYTES + 1);
            if (bytes.length > MAX_CONFIG_BYTES) return invalid("configuration exceeds " + MAX_CONFIG_BYTES + " bytes");
            JsonElement value = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!value.isJsonObject()) return invalid("configuration must be an object");
            return parse(value.getAsJsonObject());
        } catch (NoSuchFileException ex) { return defaults(); }
        catch (IOException | RuntimeException ex) { return invalid("cannot read configuration: " + ex.getMessage()); }
    }

    public static PatternObservationConfig invalid(String message) {
        return new PatternObservationConfig(new Builder(), false, "Pattern observation disabled: " + message);
    }

    public boolean eligible(String alias) {
        return valid && (symbols.isEmpty() || symbols.contains(SymbolUtils.cleanSymbol(alias).toUpperCase(Locale.ROOT)));
    }

    public static PatternObservationConfig parse(JsonObject json) {
        try {
            if (json == null) throw new IllegalArgumentException("configuration must be an object");
            Builder b = new Builder();
            if (json.has("enabled")) {
                JsonElement e = json.get("enabled");
                if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("enabled must be boolean");
                b.enabled = e.getAsBoolean();
            }
            if (json.has("symbols")) {
                if (!json.get("symbols").isJsonArray()) throw new IllegalArgumentException("symbols must be an array");
                for (JsonElement e : json.getAsJsonArray("symbols")) {
                    if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("symbols must contain strings");
                    String symbol = SymbolUtils.cleanSymbol(e.getAsString()).toUpperCase(Locale.ROOT);
                    if (symbol.isBlank()) throw new IllegalArgumentException("symbols cannot be blank");
                    b.symbols.add(symbol);
                }
                if (b.symbols.size() > 256) throw new IllegalArgumentException("at most 256 symbols allowed");
            }
            b.maxEpisodes = smallInteger(json, "maxEpisodes", b.maxEpisodes);
            b.maxWallPhases = smallInteger(json, "maxWallPhases", b.maxWallPhases);
            b.maxAttributionTrades = smallInteger(json, "maxAttributionTrades", b.maxAttributionTrades);
            JsonObject d = object(json, "detectors");
            DetectorBuilder v = b.detectors;
            v.wallLifetimeMs = integer(d, "wallLifetimeMs", v.wallLifetimeMs);
            v.clearDecisionMs = integer(d, "clearDecisionMs", v.clearDecisionMs);
            v.attributionLookbackMs = integer(d, "attributionLookbackMs", v.attributionLookbackMs);
            v.movePairWindowMs = integer(d, "movePairWindowMs", v.movePairWindowMs);
            v.holdConfirmationMs = integer(d, "holdConfirmationMs", v.holdConfirmationMs);
            v.interactionWindowMs = integer(d, "interactionWindowMs", v.interactionWindowMs);
            v.breakoutWindowMs = integer(d, "breakoutWindowMs", v.breakoutWindowMs);
            v.clearRemainingRatio = decimal(d, "clearRemainingRatio", v.clearRemainingRatio);
            v.consumptionRatio = decimal(d, "consumptionRatio", v.consumptionRatio);
            v.withdrawalMaxTradeRatio = decimal(d, "withdrawalMaxTradeRatio", v.withdrawalMaxTradeRatio);
            v.moveSizeToleranceRatio = decimal(d, "moveSizeToleranceRatio", v.moveSizeToleranceRatio);
            v.growthRatio = decimal(d, "growthRatio", v.growthRatio);
            v.fastGrowthRatio = decimal(d, "fastGrowthRatio", v.fastGrowthRatio);
            v.fastGrowthMinIncrease = integer(d, "fastGrowthMinIncrease", v.fastGrowthMinIncrease);
            v.fastGrowthWindowMs = integer(d, "fastGrowthWindowMs", v.fastGrowthWindowMs);
            v.fastGrowthMinDurationMs = integer(d, "fastGrowthMinDurationMs", v.fastGrowthMinDurationMs);
            v.fastGrowthMaxGapMs = integer(d, "fastGrowthMaxGapMs", v.fastGrowthMaxGapMs);
            v.fastGrowthMinUpdates = smallInteger(d, "fastGrowthMinUpdates", v.fastGrowthMinUpdates);
            v.fastGrowthMaxSingleStepRatio = decimal(d, "fastGrowthMaxSingleStepRatio", v.fastGrowthMaxSingleStepRatio);
            v.holdApproachRatio = decimal(d, "holdApproachRatio", v.holdApproachRatio);
            v.holdRetreatRatio = decimal(d, "holdRetreatRatio", v.holdRetreatRatio);
            v.breakoutDistanceTicks = smallInteger(d, "breakoutDistanceTicks", v.breakoutDistanceTicks);
            validate(b);
            return new PatternObservationConfig(b);
        } catch (RuntimeException ex) {
            return invalid(ex.getMessage() == null ? "invalid values" : ex.getMessage());
        }
    }

    private static void validate(Builder b) {
        positive(b.maxEpisodes, 20000, "maxEpisodes");
        positive(b.maxWallPhases, 20000, "maxWallPhases"); positive(b.maxAttributionTrades, 100000, "maxAttributionTrades");
        DetectorBuilder d = b.detectors;
        positive(d.wallLifetimeMs, 60000, "wallLifetimeMs"); positive(d.clearDecisionMs, 60000, "clearDecisionMs");
        positive(d.attributionLookbackMs, 60000, "attributionLookbackMs"); positive(d.movePairWindowMs, 60000, "movePairWindowMs");
        positive(d.holdConfirmationMs, 60000, "holdConfirmationMs"); positive(d.interactionWindowMs, 60000, "interactionWindowMs");
        positive(d.breakoutWindowMs, 60000, "breakoutWindowMs");
        require(d.holdConfirmationMs <= d.interactionWindowMs, "hold confirmation exceeds interaction window");
        ratio(d.clearRemainingRatio, "clearRemainingRatio"); ratio(d.consumptionRatio, "consumptionRatio");
        ratio(d.withdrawalMaxTradeRatio, "withdrawalMaxTradeRatio"); ratio(d.moveSizeToleranceRatio, "moveSizeToleranceRatio");
        require(d.clearRemainingRatio < 1 && d.consumptionRatio > d.withdrawalMaxTradeRatio, "clear and attribution ratios conflict");
        require(d.growthRatio > 0 && d.growthRatio <= 100, "growthRatio must be in (0,100]");
        require(d.holdApproachRatio > 0 && d.holdApproachRatio < 1, "holdApproachRatio must be in (0,1)");
        require(d.holdRetreatRatio > 0 && d.holdRetreatRatio < 1, "holdRetreatRatio must be in (0,1)");
        positive(d.breakoutDistanceTicks, 100000, "breakoutDistanceTicks");
        require(d.fastGrowthRatio > 0 && d.fastGrowthRatio <= 100, "fastGrowthRatio must be in (0,100]");
        positive(d.fastGrowthMinIncrease, Long.MAX_VALUE, "fastGrowthMinIncrease");
        positive(d.fastGrowthWindowMs, 60000, "fastGrowthWindowMs");
        positive(d.fastGrowthMinDurationMs, d.fastGrowthWindowMs, "fastGrowthMinDurationMs");
        positive(d.fastGrowthMaxGapMs, d.fastGrowthWindowMs, "fastGrowthMaxGapMs");
        require(d.fastGrowthMinUpdates >= 3 && d.fastGrowthMinUpdates <= 100000, "fastGrowthMinUpdates must be in [3,100000]");
        require(d.fastGrowthMaxSingleStepRatio > 0 && d.fastGrowthMaxSingleStepRatio < 1,
                "fastGrowthMaxSingleStepRatio must be in (0,1)");
    }
    private static void require(boolean test, String message) { if (!test) throw new IllegalArgumentException(message); }
    private static void positive(long value, long max, String key) { require(value > 0 && value <= max, key + " must be in (0," + max + "]"); }
    private static void ratio(double value, String key) { require(value >= 0 && value <= 1, key + " must be in [0,1]"); }
    private static JsonObject object(JsonObject parent, String key) {
        if (!parent.has(key)) return new JsonObject();
        if (!parent.get(key).isJsonObject()) throw new IllegalArgumentException(key + " must be an object");
        return parent.getAsJsonObject(key);
    }
    private static JsonElement numeric(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + " must be numeric");
        return value;
    }
    private static long integer(JsonObject json, String key, long fallback) {
        if (!json.has(key)) return fallback;
        try { return numeric(json, key).getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException(key + " must be an exact integer in long range"); }
    }
    private static int smallInteger(JsonObject json, String key, int fallback) {
        return Math.toIntExact(integer(json, key, fallback));
    }
    private static double decimal(JsonObject json, String key, double fallback) {
        double value = json.has(key) ? numeric(json, key).getAsDouble() : fallback;
        require(Double.isFinite(value), key + " must be finite"); return value;
    }

    /** Explicit keys survive release obfuscation; sorted symbols make the revision deterministic. */
    public JsonObject toJson() {
        JsonObject j = new JsonObject(); j.addProperty("enabled", enabled);
        JsonArray a = new JsonArray(); symbols.stream().sorted().forEach(a::add); j.add("symbols", a);
        j.addProperty("observationFloorSize", observationFloorSize);
        j.addProperty("maxEpisodes", maxEpisodes);
        j.addProperty("maxWallPhases", maxWallPhases); j.addProperty("maxAttributionTrades", maxAttributionTrades);
        JsonObject d = new JsonObject(); d.addProperty("wallLifetimeMs", detectors.wallLifetimeMs); d.addProperty("clearRemainingRatio", detectors.clearRemainingRatio);
        d.addProperty("clearDecisionMs", detectors.clearDecisionMs); d.addProperty("attributionLookbackMs", detectors.attributionLookbackMs);
        d.addProperty("consumptionRatio", detectors.consumptionRatio); d.addProperty("withdrawalMaxTradeRatio", detectors.withdrawalMaxTradeRatio);
        d.addProperty("movePairWindowMs", detectors.movePairWindowMs); d.addProperty("moveSizeToleranceRatio", detectors.moveSizeToleranceRatio);
        d.addProperty("holdApproachRatio", detectors.holdApproachRatio); d.addProperty("holdRetreatRatio", detectors.holdRetreatRatio);
        d.addProperty("holdConfirmationMs", detectors.holdConfirmationMs); d.addProperty("interactionWindowMs", detectors.interactionWindowMs);
        d.addProperty("fastGrowthRatio", detectors.fastGrowthRatio);
        d.addProperty("fastGrowthMinIncrease", detectors.fastGrowthMinIncrease);
        d.addProperty("fastGrowthWindowMs", detectors.fastGrowthWindowMs);
        d.addProperty("fastGrowthMinDurationMs", detectors.fastGrowthMinDurationMs);
        d.addProperty("fastGrowthMaxGapMs", detectors.fastGrowthMaxGapMs);
        d.addProperty("fastGrowthMinUpdates", detectors.fastGrowthMinUpdates);
        d.addProperty("fastGrowthMaxSingleStepRatio", detectors.fastGrowthMaxSingleStepRatio);
        d.addProperty("growthRatio", detectors.growthRatio); d.addProperty("breakoutDistanceTicks", detectors.breakoutDistanceTicks);
        d.addProperty("breakoutWindowMs", detectors.breakoutWindowMs); j.add("detectors", d);
        return j;
    }
    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(Integer.toHexString((b & 255) | 256).substring(1));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    static final class Builder {
        boolean enabled = true;
        Set<String> symbols = new LinkedHashSet<>();
        int maxEpisodes = 2048, maxWallPhases = 4096, maxAttributionTrades = 8192;
        DetectorBuilder detectors = new DetectorBuilder();
    }

    static final class DetectorBuilder {
        long wallLifetimeMs = 500, clearDecisionMs = 500, attributionLookbackMs = 2000, movePairWindowMs = 500;
        long holdConfirmationMs = 500, interactionWindowMs = 15000, breakoutWindowMs = 3000;
        double clearRemainingRatio = .1, consumptionRatio = .7, withdrawalMaxTradeRatio = .1;
        double moveSizeToleranceRatio = .1, growthRatio = .25;
        double holdApproachRatio = .0005, holdRetreatRatio = .001;
        double fastGrowthRatio = .5;
        long fastGrowthMinIncrease = 5000, fastGrowthWindowMs = 5000;
        long fastGrowthMinDurationMs = 2000, fastGrowthMaxGapMs = 500;
        int fastGrowthMinUpdates = 5;
        double fastGrowthMaxSingleStepRatio = .8;
        int breakoutDistanceTicks = 1;
    }
    public static final class DetectorSettings {
        public final long wallLifetimeMs, clearDecisionMs, attributionLookbackMs, movePairWindowMs;
        public final long holdConfirmationMs, interactionWindowMs, breakoutWindowMs;
        public final double clearRemainingRatio, consumptionRatio, withdrawalMaxTradeRatio, moveSizeToleranceRatio, growthRatio;
        public final double holdApproachRatio, holdRetreatRatio;
        public final int breakoutDistanceTicks;
        public final double fastGrowthRatio;
        public final long fastGrowthMinIncrease, fastGrowthWindowMs, fastGrowthMinDurationMs, fastGrowthMaxGapMs;
        public final int fastGrowthMinUpdates;
        public final double fastGrowthMaxSingleStepRatio;
        private DetectorSettings(DetectorBuilder b) {
            wallLifetimeMs = b.wallLifetimeMs; clearDecisionMs = b.clearDecisionMs;
            attributionLookbackMs = b.attributionLookbackMs; movePairWindowMs = b.movePairWindowMs;
            holdConfirmationMs = b.holdConfirmationMs; interactionWindowMs = b.interactionWindowMs; breakoutWindowMs = b.breakoutWindowMs;
            clearRemainingRatio = b.clearRemainingRatio; consumptionRatio = b.consumptionRatio;
            withdrawalMaxTradeRatio = b.withdrawalMaxTradeRatio; moveSizeToleranceRatio = b.moveSizeToleranceRatio;
            fastGrowthRatio = b.fastGrowthRatio; fastGrowthMinIncrease = b.fastGrowthMinIncrease;
            fastGrowthWindowMs = b.fastGrowthWindowMs; fastGrowthMinDurationMs = b.fastGrowthMinDurationMs;
            fastGrowthMaxGapMs = b.fastGrowthMaxGapMs; fastGrowthMinUpdates = b.fastGrowthMinUpdates;
            fastGrowthMaxSingleStepRatio = b.fastGrowthMaxSingleStepRatio;
            growthRatio = b.growthRatio; holdApproachRatio = b.holdApproachRatio;
            holdRetreatRatio = b.holdRetreatRatio; breakoutDistanceTicks = b.breakoutDistanceTicks;
        }
    }
}
