package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.PriceHistoryApi;
import com.bookmap.plugin.rong.miniviteapp.ports.HttpPort;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class SchwabPriceHistoryTest {
    private static final long START = Instant.parse("2026-10-14T05:00:00Z").toEpochMilli();
    private static final long OPEN = Instant.parse("2026-10-14T13:30:00Z").toEpochMilli();
    private static String bar(long time, String volume) {
        return "{\"datetime\":" + time + ",\"open\":71,\"high\":72,\"low\":70,\"close\":71,\"volume\":" + volume + "}";
    }
    @Test void requestsExtendedMinuteBarsAndExcludesRegularAndPriorSessionVolume() throws Exception {
        PriceHistoryApi api = new PriceHistoryApi((uri, method, headers, body) -> {
            assertEquals("api.schwabapi.com", uri.getHost());
            assertEquals("/marketdata/v1/pricehistory", uri.getPath());
            assertTrue(uri.getQuery().contains("frequencyType=minute&frequency=1"));
            assertTrue(uri.getQuery().contains("needExtendedHoursData=true"));
            assertTrue(uri.getQuery().contains("startDate=" + START));
            assertTrue(uri.getQuery().contains("endDate=" + (OPEN - 1)));
            assertEquals("Bearer test-token", headers.get("Authorization"));
            return new HttpPort.Response(200, "{\"candles\":["
                    + bar(START - 60000, "9000000") + "," + bar(START, "500000") + ","
                    + bar(OPEN - 60000, "2609249") + "," + bar(OPEN - 60000, "2609249") + ","
                    + bar(OPEN, "17000000") + "]}");
        });
        var bars = api.getMinuteBars("CRWV", START, OPEN - 1, "test-token");
        assertEquals(2, bars.size());
        assertEquals(3109249, bars.stream().mapToDouble(candle -> candle.volume).sum());
    }
    @Test void missingEmptyOrInvalidHistoryDoesNotBecomeZeroVolume() {
        for (String json : new String[]{"{}", "{\"empty\":true,\"candles\":[]}",
                "{\"candles\":[" + bar(START, "-1") + "]}"}) {
            PriceHistoryApi api = new PriceHistoryApi((uri, method, headers, body) -> new HttpPort.Response(200, json));
            assertThrows(IOException.class, () -> api.getMinuteBars("CRWV", START, OPEN - 1, "test-token"));
        }
    }
    @Test void deniedHistoryFailsForFallbackAndRetry() {
        PriceHistoryApi api = new PriceHistoryApi((uri, method, headers, body) -> new HttpPort.Response(401, "{}"));
        assertThrows(IOException.class, () -> api.getMinuteBars("CRWV", START, OPEN - 1, "test-token"));
    }
}
