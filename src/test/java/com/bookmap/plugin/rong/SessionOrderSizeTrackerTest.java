package com.bookmap.plugin.rong;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionOrderSizeTrackerTest {
    static final long OPEN = time("2026-10-16T09:30:00");
    private static long time(String value) {
        return LocalDateTime.parse(value).atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli() * 1_000_000L;
    }
    static class Rig {
        final SignalWebSocketServer server = new SignalWebSocketServer(0, 0);
        final OrderBookState book = new OrderBookState();
        Rig() { server.registerSymbol("TEST", book, .01); }
        void depth(boolean bid, int price, int size, long time) {
            server.observeSessionDepth("TEST", bid, price, size, time); book.update(bid, price, size);
        }
        void trade(int price, int size, Boolean buy, long time) { server.observeSessionTrade("TEST", price, size, buy, time, true); }
        long largest() { return server.entrySizeCap("TEST").get("largestOrderSize").getAsLong(); }
    }
    @Test void canceledLargeWallAndLaterCrossingNeverCountAsFills() {
        Rig rig = new Rig(); rig.depth(true, 10000, 20000, OPEN);
        assertEquals(0, rig.largest());
        rig.depth(true, 10000, 0, OPEN + 1);
        rig.server.updateRegularSessionHighLow("TEST", 99, OPEN + 2);
        rig.trade(9900, 30000, false, OPEN + 2);
        rig.server.updateRegularSessionHighLow("TEST", 101, OPEN + 3);
        rig.trade(10100, 30000, true, OPEN + 3);
        assertEquals(0, rig.largest());
    }
    @Test void partialExecutionsAccumulateAtOneWallAndEqualityQualifies() {
        Rig rig = new Rig(); rig.depth(false, 10000, 10000, OPEN);
        rig.trade(10000, 2000, true, OPEN + 1); assertEquals(2000, rig.largest());
        rig.depth(false, 10000, 8000, OPEN + 2);
        rig.trade(10000, 3000, true, OPEN + 3); assertEquals(5000, rig.largest());
        assertEquals(1, rig.server.entrySizeCap("TEST").get("maxRiskMultiple").getAsDouble());
        assertEquals("offer", rig.server.entrySizeCap("TEST").get("largestOrderSide").getAsString());
        rig.depth(false, 10000, 0, OPEN + 4); assertEquals(5000, rig.largest());
        assertEquals((OPEN + 3) / 1_000_000L, rig.server.entrySizeCap("TEST").get("largestOrderObservedAt").getAsLong());
    }
    @Test void cancelAndRefillStartsANewEpisodeWithoutCombiningUnrelatedFills() {
        Rig rig = new Rig(); rig.depth(true, 10000, 10000, OPEN);
        rig.trade(10000, 2000, false, OPEN + 1); rig.depth(true, 10000, 0, OPEN + 2);
        rig.depth(true, 10000, 5000, OPEN + 3); rig.trade(10000, 3000, false, OPEN + 4);
        assertEquals(3000, rig.largest()); // 2K + 3K from separate episodes cannot unlock the 5K gate.
        rig.trade(10000, 2000, false, OPEN + 5); assertEquals(5000, rig.largest());
    }
    @Test void differentSidesAndPricesCannotBeAddedTogether() {
        Rig rig = new Rig(); rig.depth(true, 10000, 6000, OPEN); rig.depth(false, 10000, 6000, OPEN);
        rig.depth(false, 10100, 6000, OPEN);
        rig.trade(10000, 3000, false, OPEN + 1); rig.trade(10000, 3000, true, OPEN + 2);
        rig.trade(10100, 3000, true, OPEN + 3); assertEquals(3000, rig.largest());
    }
    @Test void wrongSideAndUnattributedTradesDoNotCountButValidLevelOneCanAttribute() {
        Rig rig = new Rig(); rig.depth(false, 10100, 10000, OPEN);
        rig.trade(10100, 6000, false, OPEN + 1); rig.trade(10100, 6000, null, OPEN + 2); assertEquals(0, rig.largest());
        rig.server.observeSessionBbo("TEST", 10000, 10100, OPEN + 3);
        rig.trade(10100, 5000, null, OPEN + 4); assertEquals(5000, rig.largest());
        rig.server.observeSessionBbo("TEST", 10100, 10100, OPEN + 5);
        rig.trade(10100, 10000, null, OPEN + 6); assertEquals(5000, rig.largest());
    }
    @Test void depthBeforeTradeIsAcceptedBrieflyButLatePrintCannotReviveACanceledWall() {
        Rig rig = new Rig(); rig.depth(true, 10000, 10000, OPEN); rig.depth(true, 10000, 0, OPEN + 1);
        rig.trade(10000, 3000, false, OPEN + 2); assertEquals(3000, rig.largest());
        rig.trade(10000, 4000, false, OPEN + 300_000_002L); assertEquals(3000, rig.largest());
    }
    @Test void onlyRegularSessionExecutionsCountAndNewDateResets() {
        Rig rig = new Rig(); rig.depth(true, 10000, 10000, OPEN - 1);
        rig.trade(10000, 9000, false, OPEN - 1); assertEquals(0, rig.largest());
        rig.trade(10000, 2000, false, OPEN); assertEquals(2000, rig.largest());
        rig.trade(10000, 9000, false, time("2026-10-16T16:00:00")); assertEquals(2000, rig.largest());
        rig.trade(10000, 9000, false, time("2026-10-17T09:30:00")); assertEquals(0, rig.largest());
        assertFalse(rig.server.entrySizeCap("TEST").get("available").getAsBoolean());
        rig.server.observeSessionTime("TEST", time("2026-10-19T09:30:00")); assertEquals(0, rig.largest());
    }
    @Test void replaySeekClearsFutureExecutionsAndNeverSeedsStaleBook() {
        Rig rig = new Rig(); rig.depth(true, 10000, 10000, OPEN + 60_000_000_000L);
        rig.trade(10000, 5000, false, OPEN + 60_000_000_001L); assertEquals(5000, rig.largest());
        rig.server.observeSessionTime("TEST", OPEN);
        rig.trade(10000, 10000, false, OPEN + 1); assertEquals(0, rig.largest());
        rig.depth(true, 10000, 4000, OPEN + 2); rig.trade(10000, 3000, false, OPEN + 3); assertEquals(3000, rig.largest());
        rig.server.observeSessionTime("TEST", OPEN - 2);
        rig.depth(true, 10000, 5000, OPEN - 1); rig.trade(10000, 5000, false, OPEN); assertEquals(5000, rig.largest());
    }
    @Test void preSnapshotAndInvalidTradesCannotUnlockAndSizesDoNotOverflowInt() {
        Rig rig = new Rig(); rig.depth(true, 10000, 10000, OPEN);
        rig.server.observeSessionTrade("TEST", 10000, 10000, false, OPEN, false); assertEquals(0, rig.largest());
        for (int price : new int[]{0, -1}) rig.trade(price, 10000, false, OPEN);
        rig.trade(10000, 0, false, OPEN); rig.trade(10000, -1, false, OPEN); assertEquals(0, rig.largest());
        rig.trade(10000, Integer.MAX_VALUE, false, OPEN + 1); rig.trade(10000, Integer.MAX_VALUE, false, OPEN + 2);
        assertEquals(2L * Integer.MAX_VALUE, rig.largest());
        rig.server.unregisterSymbol("TEST"); assertEquals(0, rig.largest());
    }
}
