package com.bookmap.plugin.rong.patterns;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
class CairoObservationConfigTest {
    @Test void separateReadOnlyEligibility() {
        CairoObservationConfig config = new CairoObservationConfig(JsonParser.parseString("{\"enabled\":true,\"observerOnly\":true,\"symbols\":[\"AAPL\"],\"detectors\":[\"BID_STEP_UP\",\"BID_REAPPEAR\",\"OFFER_STEP_DOWN\"]}").getAsJsonObject());
        assertTrue(config.observerOnly); assertTrue(config.eligible("AAPL:NASDAQ@BMD", PatternType.BID_STEP_UP)); assertTrue(config.eligible("AAPL", PatternType.BID_REAPPEAR));
        assertFalse(config.eligible("MSFT", PatternType.BID_STEP_UP)); assertFalse(config.eligible("AAPL", PatternType.OFFER_STEP_DOWN));
        assertFalse(new CairoObservationConfig(new com.google.gson.JsonObject()).enabled);
    }
}
