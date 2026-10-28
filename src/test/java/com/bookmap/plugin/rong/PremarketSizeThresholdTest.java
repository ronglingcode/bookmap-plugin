package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.*;
import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import com.bookmap.plugin.rong.patterns.FalseBreakoutMonitor;
import com.bookmap.plugin.rong.patterns.ReclaimSecondBidMonitor;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PremarketSizeThresholdTest {
    static long time(String time) { return Instant.parse("2026-10-14T" + time + "Z").toEpochMilli(); }
    static Candle candle(long time, double volume) { return new Candle("TEST", time, 10, 10, 10, 10, volume, 10); }

    @Test void boundariesAndUnavailable() {
        assertEquals(5000, PremarketSizeThreshold.forVolume(Double.NaN));
        assertEquals(5000, PremarketSizeThreshold.forVolume(Double.POSITIVE_INFINITY));
        assertEquals(5000, PremarketSizeThreshold.forVolume(Double.NEGATIVE_INFINITY));
        assertEquals(5000, PremarketSizeThreshold.forVolume(-1));
        assertEquals(2000, PremarketSizeThreshold.forVolume(0));
        assertEquals(2000, PremarketSizeThreshold.forVolume(999999));
        assertEquals(5000, PremarketSizeThreshold.forVolume(1000000));
        assertEquals(5000, PremarketSizeThreshold.forVolume(1999999));
        assertEquals(10000, PremarketSizeThreshold.forVolume(2000000));
        assertEquals(10000, PremarketSizeThreshold.forVolume(2000001));
        assertEquals(15000, PremarketSizeThreshold.forVolume(3000000));
        assertEquals(15000, PremarketSizeThreshold.forVolume(3000001));
        assertEquals(20000, PremarketSizeThreshold.forVolume(4000000));
        assertEquals(20000, PremarketSizeThreshold.forVolume(4000001));
        assertEquals(25000, PremarketSizeThreshold.forVolume(5000000));
        assertEquals(25000, PremarketSizeThreshold.forVolume(5999999));
        assertEquals(30000, PremarketSizeThreshold.forVolume(6000000));
        assertEquals(30000, PremarketSizeThreshold.forVolume(6000001));
        assertEquals(30000, PremarketSizeThreshold.forVolume(6999999));
        assertEquals(35000, PremarketSizeThreshold.forVolume(7000000));
        assertEquals(35000, PremarketSizeThreshold.forVolume(7999999));
        assertEquals(40000, PremarketSizeThreshold.forVolume(8000000));
        assertEquals(40000, PremarketSizeThreshold.forVolume(8000001));
        assertEquals(40000, PremarketSizeThreshold.forVolume(9999999));
        assertEquals(40000, PremarketSizeThreshold.forVolume(10000000));
        assertEquals(40000, PremarketSizeThreshold.forVolume(10709318));
        assertEquals(40000, PremarketSizeThreshold.forVolume(11000000));
        assertEquals(40000, PremarketSizeThreshold.forVolume(100000000));
        assertEquals(40000, PremarketSizeThreshold.forVolume(Double.MAX_VALUE));
    }
    @Test void aggregatesPremarketAndFinalizesAtOpenWithoutRegularVolume() {
        AtomicLong now = new AtomicLong(time("13:00:00"));
        AtomicInteger calls = new AtomicInteger();
        try (PremarketSizeThreshold policy = new PremarketSizeThreshold("TEST", now::get, now::get, (symbol, start, end) -> {
            calls.incrementAndGet(); assertEquals(time("05:00:00"), start);
            assertTrue(end < time("13:30:00"));
            return List.of(candle(start - 1, 9000000), candle(start, 1500000),
                    candle(time("13:00:00"), 1000000), candle(time("13:30:00"), 9000000));
        })) {
            policy.refresh(); assertEquals(5000, policy.threshold());
            now.set(time("13:29:00")); policy.refresh(); assertEquals(10000, policy.threshold());
            now.set(time("13:30:00")); policy.refresh(); assertEquals(2500000, policy.volume());
            now.set(time("15:00:00")); policy.refresh(); assertEquals(3, calls.get());
            now.set(time("15:00:00") + 86400000); assertEquals(5000, policy.threshold());
        }
    }
    @Test void unknownChartTimeAndFailuresUseFallbackAndRetry() {
        AtomicLong now = new AtomicLong(time("13:00:00")), market = new AtomicLong(0);
        AtomicInteger calls = new AtomicInteger();
        try (PremarketSizeThreshold policy = new PremarketSizeThreshold("TEST", market::get, now::get, (symbol, start, end) -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("unavailable");
            return List.of(candle(start, 4500000));
        })) {
            policy.refresh(); assertEquals(0, calls.get()); assertEquals(5000, policy.threshold());
            market.set(now.get()); policy.refresh(); assertEquals(5000, policy.threshold());
            policy.refresh(); assertEquals(20000, policy.threshold());
            market.set(0); assertEquals(5000, policy.threshold());
        }
    }
    @Test void historicalReplayUsesItsOwnPremarketSessionAndRewindExcludesLaterVolume() {
        AtomicLong now = new AtomicLong(time("18:00:00") + 86400000), market = new AtomicLong(time("13:30:05"));
        AtomicInteger calls = new AtomicInteger();
        try (PremarketSizeThreshold policy = new PremarketSizeThreshold("TEST", market::get, now::get, (symbol, start, end) -> {
            calls.incrementAndGet(); assertEquals(time("05:00:00"), start); assertEquals(time("13:30:00") - 1, end);
            return List.of(candle(start, 500000), candle(time("13:29:00"), 3000000));
        })) {
            policy.refresh(); assertEquals(15000, policy.threshold()); assertEquals(3500000, policy.volume());
            market.set(time("13:29:05"));
            assertEquals(500000, policy.volume()); assertEquals(2000, policy.threshold());
            policy.refresh(); assertEquals(1, calls.get());
            market.set(time("13:30:05")); assertEquals(15000, policy.threshold());
            assertEquals(1, calls.get());
        }
    }

    @Test void liveAndDelayedReplayExcludeUnfinishedPremarketMinute() {
        for (long wall : new long[]{time("13:29:05"), time("18:00:00")}) {
            AtomicLong market = new AtomicLong(time("13:29:05"));
            try (PremarketSizeThreshold policy = new PremarketSizeThreshold("TEST", market::get, () -> wall,
                    (symbol, start, end) -> {
                        assertEquals(time("13:29:00") - 1, end);
                        return List.of(candle(start, 500000), candle(time("13:29:00"), 3000000));
                    })) {
                policy.refresh(); assertEquals(500000, policy.volume()); assertEquals(2000, policy.threshold());
            }
        }
    }
    @Test void replayCrossingOpenRefreshesWithoutWaitingForSixtyRealSeconds() throws Exception {
        AtomicLong market = new AtomicLong(time("13:29:55"));
        AtomicInteger calls = new AtomicInteger();
        try (PremarketSizeThreshold policy = new PremarketSizeThreshold("TEST", market::get, () -> time("18:00:00"),
                (symbol, start, end) -> { calls.incrementAndGet(); return List.of(candle(start, 500000), candle(time("13:29:00"), 3000000)); })) {
            policy.onMarketTime();
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
                while (!Double.isFinite(policy.volume())) Thread.sleep(5);
            });
            assertEquals(2000, policy.threshold());
            market.set(time("13:30:05")); policy.onMarketTime();
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
                while (policy.threshold() != 15000) Thread.sleep(5);
            });
            assertEquals(2, calls.get());
        }
    }
    @Test void historicalSessionUsesEasternOpenAfterDaylightSavingEnds() {
        long chart = Instant.parse("2026-11-03T14:30:05Z").toEpochMilli();
        try (PremarketSizeThreshold policy = new PremarketSizeThreshold("TEST", () -> chart, () -> chart + 86400000,
                (symbol, start, end) -> {
                    assertEquals(Instant.parse("2026-11-03T06:00:00Z").toEpochMilli(), start);
                    assertEquals(Instant.parse("2026-11-03T14:30:00Z").toEpochMilli() - 1, end);
                    return List.of(candle(start, 3500000));
                })) {
            policy.refresh(); assertEquals(15000, policy.threshold());
        }
    }
    @Test void entryAndDisplayIgnorePercentileAndManualFloor() {
        AtomicLong now = new AtomicLong(time("13:00:00"));
        try (PremarketSizeThreshold policy = new PremarketSizeThreshold("TEST", now::get, now::get,
                (symbol, start, end) -> List.of(candle(start, 500000)))) {
            policy.refresh();
            SignalWebSocketServer server = new SignalWebSocketServer(0, 97);
            try {
                OrderBookState book = new OrderBookState(); book.update(true, 1000, 3000); book.update(false, 1100, 100000);
                server.registerSymbol("TEST", book, .01); server.registerSizeThreshold("TEST", policy);
                assertEquals(2000, server.getOrderbookWallThreshold("TEST", 20000).getEffectiveMinSize());
                assertEquals(2000, server.entryWallSnapshot("TEST").get("effectiveWallThreshold").getAsInt());
                assertEquals(1, server.entryWallSnapshot("TEST").getAsJsonArray("largeBids").size());
            } finally { server.shutdown(); }
        }
    }
    @Test void monitorsUseSuppliedThreshold() {
        long open = time("13:30:00") * 1000000;
        AtomicInteger threshold = new AtomicInteger(2000);
        FalseBreakoutMonitor breakout = new FalseBreakoutMonitor(threshold::get); breakout.setEnabled(true);
        breakout.onTrade(100, 1, true, open, true); breakout.onDepth(false, 110, 3000, open, true);
        assertEquals(110, breakout.snapshot().offerTick);
        threshold.set(20000); breakout.onTimestamp(open + 2000000000, true); assertNull(breakout.snapshot().offerTick);
        ReclaimSecondBidMonitor reclaim = new ReclaimSecondBidMonitor(threshold::get); reclaim.setEnabled(true);
        reclaim.onDepth(true, 100, 15000, open, true); reclaim.onDepth(false, 110, 100, open, true);
        reclaim.onTimestamp(open + 2000000000); assertNull(reclaim.snapshot().nextBidTick);
        threshold.set(2000); reclaim.onDepth(true, 100, 3000, open + 2000000000, true);
        reclaim.onTimestamp(open + 4000000000L); assertEquals(100, reclaim.snapshot().nextBidTick);
    }
    @Test void delayedSameDayChartStillFetchesPremarketAfterOpen() {
        AtomicLong now = new AtomicLong(time("14:51:00")), market = new AtomicLong(time("13:30:49"));
        try (PremarketSizeThreshold policy = new PremarketSizeThreshold("TEST", market::get, now::get,
                (symbol, start, end) -> List.of(candle(start, 4360373)))) {
            policy.refresh();
            assertEquals(4360373, policy.volume());
            assertEquals(20000, policy.threshold());
        }
    }
    @Test void laggingPremarketChartBoundsSchwabRequest() {
        AtomicLong now = new AtomicLong(time("14:51:00")), market = new AtomicLong(time("13:00:00"));
        try (PremarketSizeThreshold policy = new PremarketSizeThreshold("TEST", market::get, now::get,
                (symbol, start, end) -> { assertEquals(market.get() - 1, end); return List.of(candle(start, 500000)); })) {
            policy.refresh(); assertEquals(2000, policy.threshold());
        }
    }
}
