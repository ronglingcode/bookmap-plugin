package com.bookmap.plugin.rong.patterns;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.bookmap.plugin.rong.SignalWebSocketServer;
class CairoObservationStatusTest {
    @Test void boundedBootstrapReadinessResetAndUnknownMode() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 95);
        try {
            server.observationReady("AAPL", true); server.exportPattern(CairoObservationExportTest.signal(PatternType.BID_REAPPEAR));
            var snapshot = server.observationSnapshot(); assertEquals(2, snapshot.size());
            assertEquals("ready", snapshot.get(0).get("readiness").getAsString()); assertEquals("unknown", snapshot.get(0).get("mode").getAsString());
            assertEquals("snapshot", snapshot.get(1).get("delivery").getAsString());
            server.unregisterSymbol("AAPL"); assertTrue(server.observationSnapshot().isEmpty());
            assertNotEquals(new CairoObservationExport().envelope("AAPL", "heartbeat", "live").get("sourceInstanceId"), snapshot.get(0).get("sourceInstanceId"));
        } finally { server.shutdown(); }
    }
}
