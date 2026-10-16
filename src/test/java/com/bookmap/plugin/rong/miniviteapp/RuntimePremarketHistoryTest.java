package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.ports.CredentialPort;
import com.bookmap.plugin.rong.miniviteapp.ports.HttpPort;
import com.bookmap.plugin.rong.miniviteapp.runtime.TradingRuntime;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuntimePremarketHistoryTest {
    @Test void runtimeUsesSchwabFirstThenBoundedMassiveBackfillAndLogsContributions() throws Exception {
        long start = Instant.parse("2026-10-16T05:00:00Z").toEpochMilli();
        long early = start + 3 * 3600000, seven = start + 6 * 3600000;
        long open = Instant.parse("2026-10-16T13:30:00Z").toEpochMilli();
        List<String> requests = new ArrayList<>(), logs = new ArrayList<>();
        CredentialPort credentials = new CredentialPort() {
            public JsonObject loadSchwab() {
                return JsonParser.parseString("{\"access_token\":\"test-token\",\"expires_at\":9999999999999}").getAsJsonObject();
            }
            public void saveSchwab(JsonObject value) { fail("Fresh token must not refresh"); }
        };
        JsonObject sections = JsonParser.parseString("{\"massive\":{\"apiKey\":\"test-key\"}}").getAsJsonObject();
        HttpPort http = (uri, method, headers, body) -> {
            requests.add(uri.getHost()); assertEquals("GET", method);
            if (uri.getHost().equals("api.schwabapi.com")) {
                assertEquals("Bearer test-token", headers.get("Authorization"));
                assertTrue(uri.getQuery().contains("startDate=" + start));
                assertTrue(uri.getQuery().contains("endDate=" + (open - 1)));
                return new HttpPort.Response(200, "{\"candles\":[{\"datetime\":" + seven
                        + ",\"open\":10,\"high\":10,\"low\":10,\"close\":10,\"volume\":1957823}]}");
            }
            assertEquals("api.massive.com", uri.getHost());
            assertEquals("/v2/aggs/ticker/TSLA/range/1/minute/" + start + "/" + (open - 1), uri.getPath());
            assertTrue(uri.getQuery().contains("apiKey=test-key"));
            return new HttpPort.Response(200, "{\"status\":\"OK\",\"results\":["
                    + massiveBar(early, 1287257.25) + "," + massiveBar(seven, 9999999)
                    + "," + massiveBar(open, 9999999) + "]}");
        };
        try (TradingRuntime runtime = new TradingRuntime(http, credentials, sections,
                (uri, handlers) -> { throw new AssertionError("No streams needed"); },
                (delay, run) -> { throw new AssertionError("No timers needed"); },
                Runnable::run, () -> open, new TradingRuntime.Events() {
                    public void message(JsonObject message) { }
                    public void log(String symbol, String message) { logs.add(message); }
                    public void notify(String symbol, String message) { }
                })) {
            var bars = runtime.premarketMinuteBars("TSLA", start, open - 1);
            assertEquals(List.of("api.schwabapi.com", "api.massive.com"), requests);
            assertEquals(2, bars.size()); assertEquals(early, bars.get(0).datetime);
            assertEquals(3245080.25, bars.stream().mapToDouble(candle -> candle.volume).sum());
            assertTrue(logs.stream().anyMatch(log -> log.contains("Schwab shares=1957823.0")
                    && log.contains("Massive backfill shares=1287257.25") && log.contains("backfilled minutes=1")));
        }
    }
    private static String massiveBar(long timestamp, double volume) {
        return "{\"t\":" + timestamp + ",\"o\":10,\"h\":10,\"l\":10,\"c\":10,\"v\":" + volume + "}";
    }
}
