package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.libraries.massive.Api;
import com.bookmap.plugin.rong.miniviteapp.models.Trade;
import com.bookmap.plugin.rong.miniviteapp.ports.HttpPort;
import com.bookmap.plugin.rong.miniviteapp.runtime.MarketLoader;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketLoaderTest {
    @Test void retainedSessionLevelsFeedBothOrderSnapshotAndDisplayAfterLateLoad() throws Exception {
        long open = java.time.Instant.parse("2026-10-02T13:30:00Z").toEpochMilli();
        long late = open + 22 * 60_000;
        CountDownLatch requested = new CountDownLatch(1), release = new CountDownLatch(1);
        Api api = new Api((uri, method, headers, body) -> {
            if (uri.getPath().contains("/range/1/minute/")) {
                requested.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
                return new HttpPort.Response(200, "{\"results\":["
                        + "{\"t\":" + open + ",\"o\":234.5,\"h\":236,\"l\":233.75,\"c\":235,\"v\":100,\"vw\":235},"
                        + "{\"t\":" + (open + 60_000) + ",\"o\":235,\"h\":236.5,\"l\":232.25,\"c\":233,\"v\":100,\"vw\":234}]}" );
            }
            return new HttpPort.Response(200, "{\"results\":[]}");
        }, () -> "test-key");
        var executor = Executors.newSingleThreadExecutor();
        try (MarketLoader loader = new MarketLoader(api, executor, () -> late)) {
            var loading = loader.load("AAPL", "2026-10-02", 10000, 0, 0);
            assertTrue(requested.await(5, TimeUnit.SECONDS));
            assertFalse(loader.acceptBookmapTrade("AAPL", 220, open - 60_000));
            assertFalse(loader.acceptBookmapTrade("AAPL", 237.55, late)); // Buffered before history completes.
            release.countDown();
            var state = loading.get(10, TimeUnit.SECONDS).state;
            assertEquals(237.55, state.snapshot().get("highOfDay").getAsDouble());
            assertEquals(232.25, state.snapshot().get("lowOfDay").getAsDouble());
            assertEquals(234.5, state.snapshot().get("openPrice").getAsDouble());
            assertEquals(state.snapshot().get("highOfDay").getAsDouble(), state.sessionLevels().get("highOfDay").getAsDouble());
            assertEquals(state.snapshot().get("lowOfDay").getAsDouble(), state.sessionLevels().get("lowOfDay").getAsDouble());
            assertEquals(234.5, state.sessionLevels().get("openPrice").getAsDouble());
            assertEquals(237.55, state.orderSnapshot().get("highOfDay").getAsDouble());
            assertEquals(232.25, state.orderSnapshot().get("lowOfDay").getAsDouble());
            assertEquals(234.5, state.orderSnapshot().get("openPrice").getAsDouble());

            double volume = state.snapshot().get("totalVolume").getAsDouble();
            assertTrue(loader.acceptBookmapTrade("AAPL", 230, late + 60_000));
            assertEquals(230, state.snapshot().get("lowOfDay").getAsDouble());
            assertEquals(volume, state.snapshot().get("totalVolume").getAsDouble());
            assertFalse(loader.acceptBookmapTrade("AAPL", 300, java.time.Instant.parse("2026-10-02T20:00:00Z").toEpochMilli()));
            state.applyTrade(new Trade("AAPL", java.time.Instant.parse("2026-10-02T20:00:00Z").toEpochMilli(),
                    300, 1, "after-close", null, null, List.of()));
            assertEquals(237.55, state.snapshot().get("highOfDay").getAsDouble());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void orderSnapshotDoesNotCallTheFirstLateBarTheOpen() {
        long late = java.time.Instant.parse("2026-10-02T13:31:00Z").toEpochMilli();
        var state = new com.bookmap.plugin.rong.miniviteapp.core.marketdata.MarketState("AAPL", "2026-10-02", 10000);
        state.initialize(List.of(new com.bookmap.plugin.rong.miniviteapp.models.Candle(
                "AAPL", late, 235, 236, 234, 235, 100, 235)), late + 120_000, 0, 0);
        assertFalse(state.sessionLevels().has("openPrice"));
        assertEquals(0, state.orderSnapshot().get("openPrice").getAsDouble());
    }
    @Test void removedOrPriorSessionHistoryCannotOverwriteANewLoad() throws Exception {
        CountDownLatch requested = new CountDownLatch(1), release = new CountDownLatch(1); AtomicInteger reads = new AtomicInteger();
        Api api = new Api((uri, method, headers, body) -> { if (reads.incrementAndGet() == 1) { requested.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); } return new HttpPort.Response(200, "{\"results\":[]}"); }, () -> "test-key");
        var executor = Executors.newFixedThreadPool(2);
        try (MarketLoader loader = new MarketLoader(api, executor, () -> java.time.Instant.parse("2026-10-02T13:30:00Z").toEpochMilli())) {
            var old = loader.load("AAPL", "2026-10-01", 10000, 0, 0); assertTrue(requested.await(5, TimeUnit.SECONDS)); loader.forget("AAPL");
            var next = loader.load("AAPL", "2026-10-02", 10000, 0, 0).get(5, TimeUnit.SECONDS); release.countDown(); assertThrows(java.util.concurrent.ExecutionException.class, () -> old.get(5, TimeUnit.SECONDS));
            assertSame(next.state, loader.getState("AAPL")); assertEquals("2026-10-02", next.state.snapshot().get("date").getAsString());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    @Test void buffersLivePrintsAndBackfillsCurrentMinuteExactlyOnce() throws Exception {
        long base = java.time.Instant.parse("2026-10-01T13:30:00Z").toEpochMilli();
        CountDownLatch requested = new CountDownLatch(1), release = new CountDownLatch(1); AtomicInteger reads = new AtomicInteger();
        Api api = new Api((uri, method, headers, body) -> {
            int read = reads.incrementAndGet();
            if (read == 1) {
                requested.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
                return new HttpPort.Response(200, "{\"results\":[{\"t\":" + base + ",\"o\":99,\"h\":99,\"l\":99,\"c\":99,\"v\":9999,\"vw\":99}]}");
            }
            if (read == 4) {
                assertTrue(uri.getRawQuery().contains("timestamp.gte=" + base + "000000"));
                return new HttpPort.Response(200, "{\"results\":[{\"sip_timestamp\":\"" + (base + 1) + "000000\",\"price\":10,\"size\":100,\"sequence_number\":1},{\"sip_timestamp\":\"" + (base + 2) + "000000\",\"price\":12,\"size\":200,\"sequence_number\":2}]}");
            }
            return new HttpPort.Response(200, "{\"results\":[]}");
        }, () -> "test-key");
        var executor = Executors.newSingleThreadExecutor();
        try (MarketLoader loader = new MarketLoader(api, executor, () -> base + 30000)) {
            var loading = loader.load("AAPL", "2026-10-01", 10000, 0, 0); assertTrue(requested.await(5, TimeUnit.SECONDS));
            assertSame(loading, loader.load("AAPL", "2026-10-01", 10000, 0, 0));
            loader.acceptTrade(new Trade("AAPL", base + 1, 10, 100, "1", null, null, List.of()));
            loader.acceptTrade(new Trade("AAPL", base + 2, 12, 200, "2", null, null, List.of())); release.countDown();
            var result = loading.get(10, TimeUnit.SECONDS).state.snapshot();
            assertEquals(300, result.get("totalVolume").getAsDouble()); assertEquals(3400, result.get("totalTradingAmount").getAsDouble());
            assertEquals(12, result.get("currentPrice").getAsDouble()); assertEquals(4, reads.get());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
