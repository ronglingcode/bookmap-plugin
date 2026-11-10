package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatternObservationConfigTest {
    @TempDir Path directory;
    @Test void trackedTemplateMatchesCompiledDetectorDefaults() {
        PatternObservationConfig template = PatternObservationConfig.load(Path.of("config/pattern-observation.template.json"));
        assertTrue(template.valid,template.error);
        assertEquals(PatternObservationConfig.defaults().toJson(),template.toJson());
        assertFalse(template.toJson().has("triggerRequirements"));
    }
    @Test void invalidDetectorSettingsAndOversizeFilesFailClosed() throws Exception {
        assertFalse(PatternObservationConfig.parse(JsonParser.parseString("{\"detectors\":{\"consumptionRatio\":0.05}}").getAsJsonObject()).valid);
        Path file=directory.resolve("large.json"); Files.writeString(file," ".repeat(PatternObservationConfig.MAX_CONFIG_BYTES+1));
        assertFalse(PatternObservationConfig.load(file).valid);
    }
    @Test void symbolsAreNormalizedAndRevisionIsStableAcrossTheirOrder() {
        PatternObservationConfig a=PatternObservationConfig.parse(JsonParser.parseString("{\"symbols\":[\"nvda\",\"AMD\"]}").getAsJsonObject());
        PatternObservationConfig b=PatternObservationConfig.parse(JsonParser.parseString("{\"symbols\":[\"AMD\",\"NVDA\"]}").getAsJsonObject());
        assertTrue(a.eligible("NVDA")); assertFalse(a.eligible("TSLA")); assertEquals(a.revision,b.revision);
    }
    @Test void invalidFastGrowthThresholdsFailClosed() {
        for(String setting:new String[]{"\"fastGrowthRatio\":0","\"fastGrowthMinIncrease\":0","\"fastGrowthWindowMs\":0","\"fastGrowthMinDurationMs\":5001","\"fastGrowthMaxGapMs\":0","\"fastGrowthMinUpdates\":2","\"fastGrowthMaxSingleStepRatio\":1"}) {
            PatternObservationConfig config=PatternObservationConfig.parse(JsonParser.parseString("{\"detectors\":{"+setting+"}}").getAsJsonObject());
            assertFalse(config.valid,setting);
        }
    }

}
