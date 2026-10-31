package com.bookmap.plugin.rong;

import com.bookmap.plugin.rong.miniviteapp.core.controllers.BookmapSizeCap;
import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SquareRootLiquidityThresholdTest {
    private static final long OPEN = SessionOrderSizeTrackerTest.OPEN;

    private PremarketSizeThreshold policy(double volume) {
        long chartTime = OPEN / 1_000_000L;
        PremarketSizeThreshold policy = new PremarketSizeThreshold("TEST", () -> chartTime, () -> chartTime,
                (symbol, start, end) -> List.of(new Candle(symbol, start, 100, 100, 100, 100, volume, 100)));
        policy.refresh();
        return policy;
    }

    @Test void everyRoundedRequirementRetainsItsOwnRollingPeakBeforeHistoryPolicyArrives() {
        for (int required = 2000; required <= 40000; required += 500) {
            double volume = 1000000 * Math.pow(required / 5000.0, 2);
            try (PremarketSizeThreshold policy = policy(volume)) {
                SignalWebSocketServer server = new SignalWebSocketServer(0, 0);
                try {
                    server.registerSymbol("TEST", new OrderBookState(), .01);
                    int minimum = (required + 3) / 4;
                    server.observeSessionHistoricalTrade("TEST", 10000, minimum, true, OPEN);
                    server.observeSessionHistoricalTrade("TEST", 10100, minimum, true, OPEN + 1);
                    server.observeSessionHistoricalTrade("TEST", 10200, required - 2 * minimum, true, OPEN + 2);
                    server.observeSessionTime("TEST", OPEN + 60_000_000_000L);
                    server.registerSizeThreshold("TEST", policy);
                    JsonObject cap = server.entrySizeCap("TEST");
                    assertEquals(required, cap.get("requiredOrderSize").getAsInt());
                    assertEquals(minimum, cap.get("rollingContributorMinimum").getAsInt());
                    assertEquals(required, cap.get("rollingOrderSize").getAsInt(), "requirement=" + required);
                    assertEquals(3, cap.get("rollingContributorCount").getAsInt());
                    assertTrue(cap.get("largestOrderSize").getAsInt() < required);
                    assertTrue(BookmapSizeCap.liquidityQualified(cap));
                } finally { server.shutdown(); }
            }
        }
    }

    @Test void sevenThousandRequirementExcludesSmallerFallbackContributorsAfterWindowExpires() {
        try (PremarketSizeThreshold policy = policy(2000000)) {
            SignalWebSocketServer server = new SignalWebSocketServer(0, 0);
            try {
                server.registerSymbol("TEST", new OrderBookState(), .01);
                server.observeSessionHistoricalTrade("TEST", 10000, 1500, true, OPEN);
                server.observeSessionHistoricalTrade("TEST", 10100, 1500, true, OPEN + 1);
                server.observeSessionHistoricalTrade("TEST", 10200, 4000, true, OPEN + 2);
                assertEquals(7000, server.entrySizeCap("TEST").get("rollingOrderSize").getAsInt());
                server.observeSessionTime("TEST", OPEN + 60_000_000_000L);
                server.registerSizeThreshold("TEST", policy);
                JsonObject cap = server.entrySizeCap("TEST");
                assertEquals(7000, cap.get("requiredOrderSize").getAsInt());
                assertEquals(1750, cap.get("rollingContributorMinimum").getAsInt());
                assertEquals(4000, cap.get("rollingOrderSize").getAsInt());
                assertFalse(BookmapSizeCap.liquidityQualified(cap));
            } finally { server.shutdown(); }
        }
    }
}
