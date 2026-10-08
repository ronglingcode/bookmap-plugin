package com.bookmap.plugin.rong.patterns;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
class CairoObservationConfigTest {
 @Test void legacyPreferencesEnableAggregateExportForSelectedSymbols() {
  CairoObservationConfig config = new CairoObservationConfig(JsonParser.parseString("{\"enabled\":true,\"observerOnly\":true,\"symbols\":[\"AAPL\"],\"detectors\":[\"BID_STEP_UP\"]}").getAsJsonObject());
  assertTrue(config.observerOnly); assertTrue(config.exportsPatterns("AAPL:NASDAQ@BMD")); assertFalse(config.exportsPatterns("MSFT"));
 }
 @Test void evidencePreferenceMigratesToAggregatesWithoutRawExport() {
  CairoObservationConfig config = new CairoObservationConfig(JsonParser.parseString("{\"evidenceEnabled\":true,\"evidenceSymbols\":[\"PCVX\"],\"sourceMode\":\"replay\"}").getAsJsonObject());
  assertTrue(config.exportsPatterns("PCVX")); assertFalse(config.exportsPatterns("AAPL")); assertEquals("replay",config.sourceMode);
 }
}
