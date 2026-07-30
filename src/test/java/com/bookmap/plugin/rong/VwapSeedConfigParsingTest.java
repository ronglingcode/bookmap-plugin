package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class VwapSeedConfigParsingTest {

    @Test
    void validSeedIsDeliveredToTheMatchingSymbolListener() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90, 1000);
        AtomicReference<VwapSeedDefinition> seedRef = new AtomicReference<>();
        server.registerVwapSeedListener("AAPL", seedRef::set);
        long handoffMs = timestampMs(2026, 7, 28, 9, 5);

        server.onMessage(null, "{"
                + "\"type\":\"vwap_seed\","
                + "\"priceUnit\":\"real\","
                + "\"symbol\":\"AAPL\","
                + "\"sessionDate\":\"2026-07-28\","
                + "\"continueFromTimeMs\":" + handoffMs + ","
                + "\"cumulativeVolume\":1000,"
                + "\"cumulativeNotional\":100000,"
                + "\"sentAtMs\":" + (handoffMs + 1000)
                + "}");

        VwapSeedDefinition seed = seedRef.get();
        assertEquals("AAPL", seed.getSymbol());
        assertEquals(LocalDate.of(2026, 7, 28), seed.getSessionDate());
        assertEquals(handoffMs, seed.getContinueFromTimeMs());
        assertEquals(100.0, seed.getVwap(), 0.00001);
    }

    @Test
    void seedAtTheOldNineAmBoundaryIsRejected() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90, 1000);
        AtomicReference<VwapSeedDefinition> seedRef = new AtomicReference<>();
        server.registerVwapSeedListener("AAPL", seedRef::set);
        long oldHandoffMs = timestampMs(2026, 7, 28, 9, 0);

        server.onMessage(null, "{"
                + "\"type\":\"vwap_seed\","
                + "\"symbol\":\"AAPL\","
                + "\"sessionDate\":\"2026-07-28\","
                + "\"continueFromTimeMs\":" + oldHandoffMs + ","
                + "\"cumulativeVolume\":1000,"
                + "\"cumulativeNotional\":100000"
                + "}");

        assertNull(seedRef.get());
    }

    private static long timestampMs(
            int year,
            int month,
            int day,
            int hour,
            int minute) {
        return ZonedDateTime.of(
                LocalDate.of(year, month, day),
                LocalTime.of(hour, minute),
                VwapSeedDefinition.NEW_YORK_TIME)
                .toInstant()
                .toEpochMilli();
    }
}
