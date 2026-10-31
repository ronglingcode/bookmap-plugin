package com.bookmap.plugin.rong;

import com.bookmap.plugin.rong.miniviteapp.core.controllers.BookmapSizeCap;
import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollingLiquidityTest {
    private static final long OPEN = SessionOrderSizeTrackerTest.OPEN;
    private static final long SECOND = 1_000_000_000L;

    private static class Rig implements AutoCloseable {
        final SignalWebSocketServer server = new SignalWebSocketServer(0, 0);
        final OrderBookState book = new OrderBookState();
        final PremarketSizeThreshold policy = new PremarketSizeThreshold("TEST", () -> OPEN / 1_000_000,
                () -> OPEN / 1_000_000, (symbol, start, end) -> List.of(
                        new Candle(symbol, start, 100, 100, 100, 100, 64000000, 100)));
        Rig() {
            server.registerSymbol("TEST", book, .01); policy.refresh();
            server.registerSizeThreshold("TEST", policy); server.observeSessionTime("TEST", OPEN);
        }
        void depth(boolean bid, int price, int size, long offset) {
            server.observeSessionDepth("TEST", bid, price, size, OPEN + offset); book.update(bid, price, size);
        }
        void fill(boolean bid, int price, int size, long offset) {
            depth(bid, price, size, offset);
            server.observeSessionTrade("TEST", price, size, !bid, OPEN + offset, true);
        }
        void history(boolean bid, int price, int size, long offset) {
            server.observeSessionHistoricalTrade("TEST", price, size, !bid, OPEN + offset);
        }
        JsonObject cap() { return server.entrySizeCap("TEST"); }
        long best() { return cap().get("rollingOrderSize").getAsLong(); }
        @Override public void close() { policy.close(); server.shutdown(); }
    }

    @Test void distantPricesCombineAtTwoSecondsInclusiveAndQualificationLastsForSession() {
        try (Rig rig = new Rig()) {
            rig.fill(true, 10000, 30000, 0);
            rig.fill(true, 9500, 15000, 2 * SECOND);
            assertEquals(45000, rig.best());
            assertEquals(30000, rig.cap().get("largestOrderSize").getAsLong());
            assertEquals(10000, rig.cap().get("rollingContributorMinimum").getAsLong());
            assertEquals(2000, rig.cap().get("rollingWindowMillis").getAsLong());
            assertEquals(2, rig.cap().get("rollingContributorCount").getAsInt());
            assertEquals("bid", rig.cap().get("rollingOrderSide").getAsString());
            assertTrue(BookmapSizeCap.liquidityQualified(rig.cap()));
            rig.server.observeSessionTime("TEST", OPEN + 60 * SECOND);
            assertEquals(45000, rig.best());
            assertTrue(BookmapSizeCap.liquidityQualified(rig.cap()));
        }
    }

    @Test void moreThanTwoSecondsDoesNotCombineAndRollingWindowsCannotChain() {
        try (Rig rig = new Rig()) {
            rig.fill(false, 10000, 30000, 0);
            rig.fill(false, 10100, 15000, 2 * SECOND + 1);
            assertEquals(30000, rig.best());
            assertFalse(BookmapSizeCap.liquidityQualified(rig.cap()));
        }
        try (Rig rig = new Rig()) {
            rig.fill(false, 10000, 15000, 0);
            rig.fill(false, 10100, 15000, SECOND + SECOND / 2);
            rig.fill(false, 10200, 15000, 3 * SECOND);
            assertEquals(30000, rig.best());
            assertFalse(BookmapSizeCap.liquidityQualified(rig.cap()));
        }
    }

    @Test void anyTwoSecondIntervalCanQualifyAcrossFixedBoundariesAndRemainQualifiedLater() {
        for (boolean historical : new boolean[]{false, true}) {
            try (Rig rig = new Rig()) {
                long[] offsets = {0, 1_900_000_000L, 3_800_000_000L};
                int[] sizes = {1000, 30000, 10000};
                for (int index = 0; index < offsets.length; index++) {
                    if (historical) rig.history(false, 10000 + index * 100, sizes[index], offsets[index]);
                    else rig.fill(false, 10000 + index * 100, sizes[index], offsets[index]);
                }
                // 1.9s and 3.8s straddle the fixed [0,2] / [2,4] boundary,
                // but share a valid rolling window ending at 3.8s.
                assertEquals(40000, rig.best());
                assertEquals(30000, rig.cap().get("largestOrderSize").getAsLong());
                assertEquals(2, rig.cap().get("rollingContributorCount").getAsInt());
                assertEquals((OPEN + offsets[2]) / 1_000_000,
                        rig.cap().get("rollingOrderObservedAt").getAsLong());
                assertTrue(BookmapSizeCap.liquidityQualified(rig.cap()));

                // A later quiet interval and a smaller burst cannot revoke that result.
                rig.server.observeSessionTime("TEST", OPEN + 30 * SECOND);
                rig.fill(false, 10300, 10000, 31 * SECOND);
                assertEquals(40000, rig.best());
                assertTrue(BookmapSizeCap.liquidityQualified(rig.cap()));
            }
        }
    }

    @Test void contributorsBelowTenThousandAreExcludedButPartialsCanReachTheFloorExactly() {
        try (Rig rig = new Rig()) {
            rig.fill(false, 10000, 30000, 0);
            rig.fill(false, 10100, 9000, SECOND / 2);
            rig.fill(false, 10200, 8000, SECOND);
            assertEquals(30000, rig.best());
            assertFalse(BookmapSizeCap.liquidityQualified(rig.cap()));
            rig.server.observeSessionTrade("TEST", 10100, 1000, true, OPEN + SECOND, true);
            assertEquals(40000, rig.best());
            assertEquals(2, rig.cap().get("rollingContributorCount").getAsInt());
            assertTrue(BookmapSizeCap.liquidityQualified(rig.cap()));
        }
    }

    @Test void expiredPartialsCannotBeImportedFromLifetimeWallTotals() {
        try (Rig rig = new Rig()) {
            rig.fill(true, 10000, 1000, 0);
            rig.server.observeSessionTrade("TEST", 10000, 9000, false, OPEN + SECOND / 10, true);
            assertEquals(10000, rig.best());
            // The wall now has 10K lifetime fills but just 9K inside the trailing window.
            rig.fill(true, 9900, 30000, 2 * SECOND + 1);
            assertEquals(30000, rig.best());
            assertFalse(BookmapSizeCap.liquidityQualified(rig.cap()));
        }
    }

    @Test void bidAndOfferTotalsStaySeparate() {
        try (Rig rig = new Rig()) {
            rig.fill(true, 10000, 30000, 0);
            rig.fill(false, 10100, 30000, SECOND);
            assertEquals(30000, rig.best());
            assertFalse(BookmapSizeCap.liquidityQualified(rig.cap()));
        }
    }

    @Test void separateEpisodesAtSamePriceMustMeetTheContributorFloorIndividually() {
        try (Rig rig = new Rig()) {
            rig.fill(true, 10000, 6000, 0);
            rig.depth(true, 10000, 0, 1);
            rig.fill(true, 10000, 6000, 2);
            rig.fill(true, 9900, 30000, SECOND);
            assertEquals(30000, rig.best());
            rig.server.observeSessionTrade("TEST", 10000, 4000, false, OPEN + SECOND, true);
            assertEquals(40000, rig.best());
        }
    }

    @Test void historicalPrintsUseSameWindowAndFloorWithoutDoubleCountingMatchingDepth() {
        try (Rig rig = new Rig()) {
            rig.server.beginSessionHistory("TEST");
            rig.depth(false, 10000, 30000, 0);
            rig.history(false, 10000, 30000, 0);
            assertEquals(30000, rig.best()); // Matching depth plus print is still one contribution.
            rig.history(false, 10100, 6000, SECOND / 4);
            rig.history(false, 10100, 6000, SECOND / 2);
            assertEquals(30000, rig.best()); // No depth: separate 6K prints cannot form a 12K wall.
            rig.history(false, 10200, 10000, SECOND);
            assertEquals(40000, rig.best());
            assertFalse(BookmapSizeCap.liquidityQualified(rig.cap()));
            rig.server.finishSessionHistory("TEST");
            assertTrue(BookmapSizeCap.liquidityQualified(rig.cap()));
            assertEquals(2, rig.cap().get("rollingContributorCount").getAsInt());
        }
    }

    @Test void finalPremarketTierReevaluatesHistoricalContributorsRatherThanReusingFallbackPeak() {
        try (Rig rig = new Rig()) {
            // Deliver history before its premarket policy is registered (fallback requires 5K).
            SignalWebSocketServer server = new SignalWebSocketServer(0, 0);
            try {
                server.registerSymbol("TEST", new OrderBookState(), .01);
                server.observeSessionHistoricalTrade("TEST", 10000, 9000, true, OPEN);
                server.observeSessionHistoricalTrade("TEST", 10100, 9000, true, OPEN + 1);
                server.observeSessionHistoricalTrade("TEST", 10200, 22000, true, OPEN + 2);
                assertEquals(40000, server.entrySizeCap("TEST").get("rollingOrderSize").getAsLong());
                server.registerSizeThreshold("TEST", rig.policy);
                assertEquals(22000, server.entrySizeCap("TEST").get("rollingOrderSize").getAsLong());
                assertFalse(BookmapSizeCap.liquidityQualified(server.entrySizeCap("TEST")));
            } finally { server.shutdown(); }
        }
    }

    @Test void canceledUnreadyUnattributedAndOutOfSessionExecutionsCannotContribute() {
        try (Rig rig = new Rig()) {
            rig.depth(false, 10000, 30000, 0);
            rig.depth(false, 10000, 0, 1);
            rig.server.observeSessionTrade("TEST", 10000, 30000, true, OPEN + SECOND, true);
            rig.depth(false, 10100, 30000, SECOND);
            rig.server.observeSessionTrade("TEST", 10100, 30000, true, OPEN + SECOND, false);
            rig.server.observeSessionTrade("TEST", 10100, 30000, null, OPEN + SECOND, true);
            rig.server.observeSessionTrade("TEST", 10100, 30000, false, OPEN + SECOND, true);
            rig.server.observeSessionHistoricalTrade("TEST", 10100, 30000, true, OPEN + 23400 * SECOND);
            assertEquals(0, rig.best());
            assertFalse(BookmapSizeCap.liquidityQualified(rig.cap()));
        }
    }

    @Test void rewindNewSessionAndDetachDiscardBothRollingPeakAndActiveContributors() {
        try (Rig rig = new Rig()) {
            rig.fill(true, 10000, 30000, SECOND);
            rig.fill(true, 9900, 10000, SECOND + 1);
            assertEquals(40000, rig.best());
            rig.server.observeSessionTime("TEST", OPEN);
            assertEquals(0, rig.best());
            rig.fill(true, 9800, 10000, 1);
            assertEquals(10000, rig.best());
            rig.server.observeSessionTime("TEST", OPEN + 3 * 86400 * SECOND);
            assertEquals(0, rig.best());
            rig.server.observeSessionHistoricalTrade("TEST", 10000, 30000, true, OPEN + 3 * 86400 * SECOND);
            assertEquals(30000, rig.best());
            rig.server.unregisterSymbol("TEST");
            assertEquals(0, rig.best());
        }
    }

    @Test void singleWallCanQualifyOverLongerThanTwoSecondsWithoutRollingQualification() {
        try (Rig rig = new Rig()) {
            rig.fill(true, 10000, 30000, 0);
            rig.server.observeSessionTrade("TEST", 10000, 10000, false, OPEN + 3 * SECOND, true);
            assertEquals(30000, rig.best());
            assertEquals(40000, rig.cap().get("largestOrderSize").getAsLong());
            assertTrue(BookmapSizeCap.liquidityQualified(rig.cap()));
        }
    }
}
