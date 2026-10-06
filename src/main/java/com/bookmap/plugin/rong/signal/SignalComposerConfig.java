package com.bookmap.plugin.rong.signal;

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
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;

/** Immutable rules snapshot. File settings never belong to the broker configuration. */
public final class SignalComposerConfig {
    public static final String CONFIG_PROPERTY = "bmtrader.signalComposerConfig";
    public static final int MAX_CONFIG_BYTES = 16384;
    public final boolean enabled, valid;
    public final String error, revision;
    public final Set<String> symbols;
    public final long normalTriggerSize, minimumTriggerSize, normalConfirmationSize, observationFloorSize;
    public final long historyRetentionMs, beforeWindowMs, afterWindowMs;
    public final int maxPriceDistanceTicks, directionalPriceToleranceTicks, maxTriggerDriftTicks;
    public final int maxEvents, maxCandidates, maxWallPhases, maxAttributionTrades;
    public final StrengthMultiples strengthMultiples;
    public final TriggerRequirements triggerRequirements;
    public final DetectorSettings detectors;

    private SignalComposerConfig(Builder b) {
        this(b, true, "");
    }
    private SignalComposerConfig(Builder b, boolean valid, String error) {
        this.valid = valid; this.error = error; enabled = valid && b.enabled;
        symbols = Collections.unmodifiableSet(new LinkedHashSet<>(b.symbols));
        normalTriggerSize = b.normalTriggerSize; minimumTriggerSize = b.minimumTriggerSize;
        normalConfirmationSize = b.normalConfirmationSize; observationFloorSize = b.observationFloorSize;
        historyRetentionMs = b.historyRetentionMs; beforeWindowMs = b.beforeWindowMs; afterWindowMs = b.afterWindowMs;
        maxPriceDistanceTicks = b.maxPriceDistanceTicks; directionalPriceToleranceTicks = b.directionalPriceToleranceTicks;
        maxTriggerDriftTicks = b.maxTriggerDriftTicks; maxEvents = b.maxEvents; maxCandidates = b.maxCandidates;
        maxWallPhases = b.maxWallPhases; maxAttributionTrades = b.maxAttributionTrades;
        strengthMultiples = b.strengthMultiples; triggerRequirements = b.triggerRequirements;
        detectors = new DetectorSettings(b.detectors);
        revision = digest(toJson().toString());
    }

    public static SignalComposerConfig defaults() { return new SignalComposerConfig(new Builder()); }

    public static SignalComposerConfig load() {
        try {
            String fallback = Path.of(System.getProperty("user.home"), "bmtrader", "signal-composer.json").toString();
            return load(Path.of(System.getProperty(CONFIG_PROPERTY, fallback)));
        } catch (RuntimeException ex) { return invalid("cannot locate configuration: " + ex.getMessage()); }
    }

    public static SignalComposerConfig load(Path path) {
        try (InputStream stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(MAX_CONFIG_BYTES + 1);
            if (bytes.length > MAX_CONFIG_BYTES) return invalid("configuration exceeds " + MAX_CONFIG_BYTES + " bytes");
            JsonElement value = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!value.isJsonObject()) return invalid("configuration must be an object");
            return parse(value.getAsJsonObject());
        } catch (NoSuchFileException ex) { return defaults(); }
        catch (IOException | RuntimeException ex) { return invalid("cannot read configuration: " + ex.getMessage()); }
    }

    public static SignalComposerConfig invalid(String message) {
        return new SignalComposerConfig(new Builder(), false, "SignalComposer disabled: " + message);
    }

    public boolean eligible(String alias) {
        return valid && (symbols.isEmpty() || symbols.contains(SymbolUtils.cleanSymbol(alias).toUpperCase(Locale.ROOT)));
    }

    public static SignalComposerConfig parse(JsonObject json) {
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
            b.normalTriggerSize = integer(json, "normalTriggerSize", b.normalTriggerSize);
            b.minimumTriggerSize = integer(json, "minimumTriggerSize", b.minimumTriggerSize);
            b.normalConfirmationSize = integer(json, "normalConfirmationSize", b.normalConfirmationSize);
            b.observationFloorSize = integer(json, "observationFloorSize", b.observationFloorSize);
            b.historyRetentionMs = integer(json, "historyRetentionMs", b.historyRetentionMs);
            b.beforeWindowMs = integer(json, "beforeWindowMs", b.beforeWindowMs);
            b.afterWindowMs = integer(json, "afterWindowMs", b.afterWindowMs);
            b.maxPriceDistanceTicks = smallInteger(json, "maxPriceDistanceTicks", b.maxPriceDistanceTicks);
            b.directionalPriceToleranceTicks = smallInteger(json, "directionalPriceToleranceTicks", b.directionalPriceToleranceTicks);
            b.maxTriggerDriftTicks = smallInteger(json, "maxTriggerDriftTicks", b.maxTriggerDriftTicks);
            b.maxEvents = smallInteger(json, "maxEvents", b.maxEvents);
            b.maxCandidates = smallInteger(json, "maxCandidates", b.maxCandidates);
            b.maxWallPhases = smallInteger(json, "maxWallPhases", b.maxWallPhases);
            b.maxAttributionTrades = smallInteger(json, "maxAttributionTrades", b.maxAttributionTrades);
            JsonObject s = object(json, "strengthMultiples");
            b.strengthMultiples = new StrengthMultiples(decimal(s, "normal", 1), decimal(s, "strong", 2),
                    decimal(s, "veryStrong", 5), decimal(s, "exceptional", 10));
            JsonObject t = object(json, "triggerRequirements");
            b.triggerRequirements = new TriggerRequirements(integer(t, "none", 5000), integer(t, "belowNormal", 5000),
                    integer(t, "normal", 5000), integer(t, "strong", 5000), integer(t, "veryStrong", 4000), integer(t, "exceptional", 3000));
            JsonObject d = object(json, "detectors");
            DetectorBuilder v = b.detectors;
            v.wallLifetimeMs = integer(d, "wallLifetimeMs", v.wallLifetimeMs);
            v.clearDecisionMs = integer(d, "clearDecisionMs", v.clearDecisionMs);
            v.attributionLookbackMs = integer(d, "attributionLookbackMs", v.attributionLookbackMs);
            v.movePairWindowMs = integer(d, "movePairWindowMs", v.movePairWindowMs);
            v.rejectionHoldMs = integer(d, "rejectionHoldMs", v.rejectionHoldMs);
            v.interactionWindowMs = integer(d, "interactionWindowMs", v.interactionWindowMs);
            v.breakoutWindowMs = integer(d, "breakoutWindowMs", v.breakoutWindowMs);
            v.clearRemainingRatio = decimal(d, "clearRemainingRatio", v.clearRemainingRatio);
            v.consumptionRatio = decimal(d, "consumptionRatio", v.consumptionRatio);
            v.withdrawalMaxTradeRatio = decimal(d, "withdrawalMaxTradeRatio", v.withdrawalMaxTradeRatio);
            v.moveSizeToleranceRatio = decimal(d, "moveSizeToleranceRatio", v.moveSizeToleranceRatio);
            v.growthRatio = decimal(d, "growthRatio", v.growthRatio);
            v.approachDistanceTicks = smallInteger(d, "approachDistanceTicks", v.approachDistanceTicks);
            v.rejectionDistanceTicks = smallInteger(d, "rejectionDistanceTicks", v.rejectionDistanceTicks);
            v.breakoutDistanceTicks = smallInteger(d, "breakoutDistanceTicks", v.breakoutDistanceTicks);
            validate(b);
            return new SignalComposerConfig(b);
        } catch (RuntimeException ex) {
            return invalid(ex.getMessage() == null ? "invalid values" : ex.getMessage());
        }
    }

    private static void validate(Builder b) {
        positive(b.normalTriggerSize, Long.MAX_VALUE, "normalTriggerSize");
        positive(b.minimumTriggerSize, b.normalTriggerSize, "minimumTriggerSize");
        positive(b.normalConfirmationSize, Long.MAX_VALUE, "normalConfirmationSize");
        positive(b.observationFloorSize, b.minimumTriggerSize, "observationFloorSize");
        positive(b.beforeWindowMs, 600000, "beforeWindowMs"); positive(b.afterWindowMs, 600000, "afterWindowMs");
        positive(b.historyRetentionMs, 3600000, "historyRetentionMs");
        require(b.historyRetentionMs >= Math.max(b.beforeWindowMs, b.afterWindowMs), "history retention must cover both windows");
        positive(b.maxEvents, 20000, "maxEvents"); positive(b.maxCandidates, 1024, "maxCandidates");
        positive(b.maxWallPhases, 20000, "maxWallPhases"); positive(b.maxAttributionTrades, 100000, "maxAttributionTrades");
        positive(b.maxPriceDistanceTicks, 100000, "maxPriceDistanceTicks"); positive(b.maxTriggerDriftTicks, 100000, "maxTriggerDriftTicks");
        require(b.directionalPriceToleranceTicks >= 0 && b.directionalPriceToleranceTicks <= b.maxPriceDistanceTicks, "directional price tolerance exceeds distance");
        StrengthMultiples s = b.strengthMultiples;
        require(s.normal > 0 && s.normal < s.strong && s.strong < s.veryStrong && s.veryStrong < s.exceptional,
                "strength multiples must be positive and strictly increasing");
        TriggerRequirements t = b.triggerRequirements;
        require(t.none == b.normalTriggerSize && t.belowNormal == t.none && t.normal == t.none && t.strong == t.none,
                "unrelaxed requirements must equal normalTriggerSize");
        require(t.veryStrong <= t.strong && t.exceptional <= t.veryStrong && t.exceptional >= b.minimumTriggerSize,
                "trigger requirements must decrease to no less than minimumTriggerSize");
        DetectorBuilder d = b.detectors;
        positive(d.wallLifetimeMs, 60000, "wallLifetimeMs"); positive(d.clearDecisionMs, 60000, "clearDecisionMs");
        positive(d.attributionLookbackMs, 60000, "attributionLookbackMs"); positive(d.movePairWindowMs, 60000, "movePairWindowMs");
        positive(d.rejectionHoldMs, 60000, "rejectionHoldMs"); positive(d.interactionWindowMs, 60000, "interactionWindowMs");
        positive(d.breakoutWindowMs, 60000, "breakoutWindowMs");
        require(d.rejectionHoldMs <= d.interactionWindowMs, "rejection hold exceeds interaction window");
        ratio(d.clearRemainingRatio, "clearRemainingRatio"); ratio(d.consumptionRatio, "consumptionRatio");
        ratio(d.withdrawalMaxTradeRatio, "withdrawalMaxTradeRatio"); ratio(d.moveSizeToleranceRatio, "moveSizeToleranceRatio");
        require(d.clearRemainingRatio < 1 && d.consumptionRatio > d.withdrawalMaxTradeRatio, "clear and attribution ratios conflict");
        require(d.growthRatio > 0 && d.growthRatio <= 100, "growthRatio must be in (0,100]");
        positive(d.approachDistanceTicks, 100000, "approachDistanceTicks"); positive(d.rejectionDistanceTicks, 100000, "rejectionDistanceTicks");
        positive(d.breakoutDistanceTicks, 100000, "breakoutDistanceTicks");
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
        j.addProperty("normalTriggerSize", normalTriggerSize); j.addProperty("minimumTriggerSize", minimumTriggerSize);
        j.addProperty("normalConfirmationSize", normalConfirmationSize); j.addProperty("observationFloorSize", observationFloorSize);
        j.addProperty("historyRetentionMs", historyRetentionMs); j.addProperty("beforeWindowMs", beforeWindowMs); j.addProperty("afterWindowMs", afterWindowMs);
        j.addProperty("maxPriceDistanceTicks", maxPriceDistanceTicks); j.addProperty("directionalPriceToleranceTicks", directionalPriceToleranceTicks);
        j.addProperty("maxTriggerDriftTicks", maxTriggerDriftTicks); j.addProperty("maxEvents", maxEvents); j.addProperty("maxCandidates", maxCandidates);
        j.addProperty("maxWallPhases", maxWallPhases); j.addProperty("maxAttributionTrades", maxAttributionTrades);
        JsonObject s = new JsonObject(); s.addProperty("normal", strengthMultiples.normal); s.addProperty("strong", strengthMultiples.strong);
        s.addProperty("veryStrong", strengthMultiples.veryStrong); s.addProperty("exceptional", strengthMultiples.exceptional); j.add("strengthMultiples", s);
        JsonObject t = new JsonObject(); t.addProperty("none", triggerRequirements.none); t.addProperty("belowNormal", triggerRequirements.belowNormal);
        t.addProperty("normal", triggerRequirements.normal); t.addProperty("strong", triggerRequirements.strong);
        t.addProperty("veryStrong", triggerRequirements.veryStrong); t.addProperty("exceptional", triggerRequirements.exceptional); j.add("triggerRequirements", t);
        JsonObject d = new JsonObject(); d.addProperty("wallLifetimeMs", detectors.wallLifetimeMs); d.addProperty("clearRemainingRatio", detectors.clearRemainingRatio);
        d.addProperty("clearDecisionMs", detectors.clearDecisionMs); d.addProperty("attributionLookbackMs", detectors.attributionLookbackMs);
        d.addProperty("consumptionRatio", detectors.consumptionRatio); d.addProperty("withdrawalMaxTradeRatio", detectors.withdrawalMaxTradeRatio);
        d.addProperty("movePairWindowMs", detectors.movePairWindowMs); d.addProperty("moveSizeToleranceRatio", detectors.moveSizeToleranceRatio);
        d.addProperty("approachDistanceTicks", detectors.approachDistanceTicks); d.addProperty("rejectionDistanceTicks", detectors.rejectionDistanceTicks);
        d.addProperty("rejectionHoldMs", detectors.rejectionHoldMs); d.addProperty("interactionWindowMs", detectors.interactionWindowMs);
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
        boolean enabled;
        Set<String> symbols = new LinkedHashSet<>();
        long normalTriggerSize = 5000, minimumTriggerSize = 3000, normalConfirmationSize = 5000, observationFloorSize = 3000;
        long historyRetentionMs = 120000, beforeWindowMs = 30000, afterWindowMs = 30000;
        int maxPriceDistanceTicks = 20, directionalPriceToleranceTicks = 2, maxTriggerDriftTicks = 20;
        int maxEvents = 2048, maxCandidates = 64, maxWallPhases = 4096, maxAttributionTrades = 8192;
        StrengthMultiples strengthMultiples = new StrengthMultiples(1, 2, 5, 10);
        TriggerRequirements triggerRequirements = new TriggerRequirements(5000, 5000, 5000, 5000, 4000, 3000);
        DetectorBuilder detectors = new DetectorBuilder();
    }

    public static final class StrengthMultiples {
        public final double normal, strong, veryStrong, exceptional;
        StrengthMultiples(double normal, double strong, double veryStrong, double exceptional) {
            this.normal = normal; this.strong = strong; this.veryStrong = veryStrong; this.exceptional = exceptional;
        }
    }
    public static final class TriggerRequirements {
        public final long none, belowNormal, normal, strong, veryStrong, exceptional;
        TriggerRequirements(long none, long belowNormal, long normal, long strong, long veryStrong, long exceptional) {
            this.none = none; this.belowNormal = belowNormal; this.normal = normal;
            this.strong = strong; this.veryStrong = veryStrong; this.exceptional = exceptional;
        }
    }
    static final class DetectorBuilder {
        long wallLifetimeMs = 500, clearDecisionMs = 500, attributionLookbackMs = 2000, movePairWindowMs = 500;
        long rejectionHoldMs = 200, interactionWindowMs = 5000, breakoutWindowMs = 3000;
        double clearRemainingRatio = .1, consumptionRatio = .7, withdrawalMaxTradeRatio = .1;
        double moveSizeToleranceRatio = .1, growthRatio = .25;
        int approachDistanceTicks = 2, rejectionDistanceTicks = 2, breakoutDistanceTicks = 1;
    }
    public static final class DetectorSettings {
        public final long wallLifetimeMs, clearDecisionMs, attributionLookbackMs, movePairWindowMs;
        public final long rejectionHoldMs, interactionWindowMs, breakoutWindowMs;
        public final double clearRemainingRatio, consumptionRatio, withdrawalMaxTradeRatio, moveSizeToleranceRatio, growthRatio;
        public final int approachDistanceTicks, rejectionDistanceTicks, breakoutDistanceTicks;
        private DetectorSettings(DetectorBuilder b) {
            wallLifetimeMs = b.wallLifetimeMs; clearDecisionMs = b.clearDecisionMs;
            attributionLookbackMs = b.attributionLookbackMs; movePairWindowMs = b.movePairWindowMs;
            rejectionHoldMs = b.rejectionHoldMs; interactionWindowMs = b.interactionWindowMs; breakoutWindowMs = b.breakoutWindowMs;
            clearRemainingRatio = b.clearRemainingRatio; consumptionRatio = b.consumptionRatio;
            withdrawalMaxTradeRatio = b.withdrawalMaxTradeRatio; moveSizeToleranceRatio = b.moveSizeToleranceRatio;
            growthRatio = b.growthRatio; approachDistanceTicks = b.approachDistanceTicks;
            rejectionDistanceTicks = b.rejectionDistanceTicks; breakoutDistanceTicks = b.breakoutDistanceTicks;
        }
    }
}
