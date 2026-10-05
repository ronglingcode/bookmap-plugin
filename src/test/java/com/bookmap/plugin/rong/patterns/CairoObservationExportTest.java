package com.bookmap.plugin.rong.patterns;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Collections;
import com.google.gson.JsonObject;
class CairoObservationExportTest {
    static BookmapPatternSignal signal(PatternType type) { return new BookmapPatternSignal("episode-1", "AAPL", type, 10000, 100, 9999, 500, 80, Collections.emptyList(), 1791220000123456789L, System.currentTimeMillis()); }
    @Test void onlyTwoPatternsAndStableEpisodeDespiteNewUuid() {
        CairoObservationExport exporter = new CairoObservationExport();
        JsonObject first = exporter.episode(signal(PatternType.BID_STEP_UP));
        JsonObject second = exporter.episode(signal(PatternType.BID_STEP_UP));
        assertEquals(first.get("episodeId"), second.get("episodeId")); assertEquals(2, second.get("revision").getAsInt());
        assertEquals("1791220000123456789", first.get("eventTime").getAsString());
        assertEquals("USD", first.get("priceUnit").getAsString()); assertEquals(100, first.get("price").getAsDouble());
        assertNotNull(exporter.episode(signal(PatternType.BID_REAPPEAR))); assertNull(exporter.episode(signal(PatternType.OFFER_REAPPEAR))); assertNull(exporter.episode(signal(PatternType.OFFER_STEP_DOWN)));
        assertEquals("unknown", first.get("mode").getAsString());
    }
}
