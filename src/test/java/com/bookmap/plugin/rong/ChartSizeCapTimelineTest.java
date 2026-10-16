package com.bookmap.plugin.rong;

import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import com.google.gson.JsonObject;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChartSizeCapTimelineTest {
    private static long time(String value) {
        return LocalDateTime.parse(value).atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli();
    }
    private static Candle bar(long timestamp, double high, double low, double volume) {
        return new Candle("TEST", timestamp, 369.15, high, low, 369.15, volume, 369.15);
    }
    private static void depth(SignalWebSocketServer server, OrderBookState book, boolean bid, int price, int size, long timestamp) {
        server.observeSessionDepth("TEST", bid, price, size, timestamp * 1_000_000L);
        book.update(bid, price, size);
    }
    @Test void identicalEventsProduceIdenticalCapsLiveAndWithReplayHoursOrDaysBehindWallClock() {
        JsonObject baseline = null;
        for (long delay : new long[]{0, 3 * 60 * 60 * 1000L, 86400000L}) {
            long open = time("2026-10-16T09:30:00");
            AtomicLong chart = new AtomicLong(open - 5000), wall = new AtomicLong(chart.get() + delay);
            try (PremarketSizeThreshold policy = new PremarketSizeThreshold("TEST", chart::get, wall::get,
                    (symbol, start, end) -> List.of(bar(start, 370, 360, 500000), bar(open - 60000, 370, 360, 3000000)))) {
                SignalWebSocketServer server = new SignalWebSocketServer(0, 0);
                OrderBookState book = new OrderBookState(); server.registerSymbol("TEST", book, .01);
                server.registerSizeThreshold("TEST", policy);
                server.observeSessionTime("TEST", chart.get() * 1_000_000L);
                policy.refresh(); assertEquals(2000, policy.threshold());
                depth(server, book, true, 36800, 10000, chart.get());
                depth(server, book, false, 37050, 7600, chart.get());
                depth(server, book, false, 36930, 5000, chart.get());
                chart.set(open); wall.set(chart.get() + delay);
                server.observeSessionTime("TEST", chart.get() * 1_000_000L);
                server.updateRegularSessionHighLow("TEST", 369.15, chart.get() * 1_000_000L);
                policy.refresh(); assertEquals(15000, policy.threshold());
                // The later minute's full range cannot widen either live or replay's first five seconds.
                server.updateRegularSessionMinuteBars("TEST", "2026-10-16", List.of(bar(open, 370.58, 356.33, 1000000)));
                chart.set(open + 5000); wall.set(chart.get() + delay);
                server.observeSessionTime("TEST", chart.get() * 1_000_000L);
                server.updateRegularSessionHighLow("TEST", 369.30, chart.get() * 1_000_000L);
                JsonObject cap = server.entrySizeCap("TEST");
                assertEquals(5000, cap.get("largestOrderSize").getAsInt());
                assertEquals(1.0 / 3, cap.get("maxRiskMultiple").getAsDouble());
                if (baseline == null) baseline = cap; else assertEquals(baseline, cap);
                depth(server, book, true, 36800, 0, chart.get());
                chart.set(open + 6000); wall.set(chart.get() + delay);
                server.observeSessionTime("TEST", chart.get() * 1_000_000L);
                server.updateRegularSessionHighLow("TEST", 368, chart.get() * 1_000_000L);
                assertEquals(2.0 / 3, server.entrySizeCap("TEST").get("maxRiskMultiple").getAsDouble());
                assertEquals(368, server.getRegularSessionHighLow("TEST").getLowOfDay());
            }
        }
    }
}
