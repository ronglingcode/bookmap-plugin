package com.bookmap.plugin.rong;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplayTradingTest {
    @Test void replayVwapWeightsRecordedVolumeAndTracksCoverage() {
        ReplayVwap vwap = new ReplayVwap();
        long premarket = java.time.Instant.parse("2026-11-12T09:00:00Z").toEpochMilli() * 1_000_000L;
        vwap.observe(premarket);
        vwap.trade(99, 200, premarket - 1_000_000_000L);
        vwap.trade(150, 100, premarket + 1_000_000_000L);
        vwap.trade(156, 300, premarket + 2_000_000_000L);
        assertEquals(154.5, vwap.value());
        assertTrue(vwap.coversPremarketStart());
        ReplayVwap afterSeek = new ReplayVwap();
        afterSeek.trade(151, 50, premarket + 1_000_000_000L);
        assertEquals(151, afterSeek.value());
        assertFalse(afterSeek.coversPremarketStart());
    }

    @Test void sellsPartialsAndClosesWithoutOpeningAShort() {
        List<JsonObject> events = new ArrayList<>();
        ReplayTradeWindow replay = new ReplayTradeWindow("TEST", events::add);
        replay.observeTrade(148, 100);
        replay.sell(1);
        replay.buy(100);
        replay.observeTrade(150, 200);
        replay.sell(20);
        replay.sell(81);
        assertEquals(2, events.size());
        JsonObject partial = events.get(1);
        assertEquals("SELL", partial.get("side").getAsString());
        assertEquals(150, partial.get("price").getAsDouble());
        assertEquals(80, partial.get("positionQuantity").getAsInt());
        assertEquals(148, partial.get("positionAveragePrice").getAsDouble());
        assertEquals(events.get(0).get("positionId"), partial.get("positionId"));
        replay.sell(80);
        assertEquals(0, events.get(2).get("positionQuantity").getAsInt());
        replay.buy(1);
        assertTrue(events.get(3).get("opening").getAsBoolean());
        assertNotEquals(events.get(0).get("positionId"), events.get(3).get("positionId"));
    }

    @Test void separatesDataModeFromTradingMode() {
        assertEquals(BookmapRunMode.REPLAY, BookmapRunMode.fromFlags(false, false));
        assertEquals(BookmapRunMode.LIVE, BookmapRunMode.fromFlags(true, true));
        assertEquals(BookmapRunMode.LIVE_SIMULATED, BookmapRunMode.fromFlags(true, false));
        assertEquals(BookmapRunMode.UNKNOWN, BookmapRunMode.fromFlags(null, null));
    }

    @Test void publishesOpeningAndAddFillsAndDiscardsPriceAndPositionAfterRewind() {
        List<JsonObject> events = new ArrayList<>();
        ReplayTradeWindow replay = new ReplayTradeWindow("TEST", events::add);
        replay.buy(10);
        assertTrue(events.isEmpty());

        replay.observeTrade(101.25, 100);
        replay.observeTime(120);
        replay.buy(10);
        JsonObject first = events.get(0);
        assertEquals("cairo_replay_fill", first.get("type").getAsString());
        assertEquals("replay", first.get("mode").getAsString());
        assertEquals("120", first.get("eventTime").getAsString());
        assertEquals(101.25, first.get("price").getAsDouble());
        assertTrue(first.get("opening").getAsBoolean());

        replay.observeTrade(102.25, 130);
        replay.buy(10);
        assertEquals(20, events.get(1).get("positionQuantity").getAsInt());
        assertEquals(101.75, events.get(1).get("positionAveragePrice").getAsDouble());
        assertFalse(events.get(1).get("opening").getAsBoolean());

        replay.observeTime(90);
        assertEquals("cairo_replay_reset", events.get(2).get("type").getAsString());
        replay.buy(5);
        assertEquals(3, events.size());
        replay.observeTrade(99.25, 91);
        replay.buy(5);
        assertEquals(2, events.get(3).get("epoch").getAsInt());
        assertEquals(5, events.get(3).get("positionQuantity").getAsInt());
        assertTrue(events.get(3).get("opening").getAsBoolean());
        assertNotEquals(first.get("executionId"), events.get(3).get("executionId"));
    }
}
