package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.marketdata.MarketClock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedTradingHoursTest {
    @Test void extendedSessionBoundariesExcludeRegularHoursAndWeekends() {
        for (String time : new String[]{"2026-11-23T12:00:00Z", "2026-11-23T14:27:59.999Z", "2026-11-23T21:00:00Z", "2026-11-24T00:59:59Z"})
            assertTrue(MarketClock.isExtendedTradingTime(Instant.parse(time).toEpochMilli()), time);
        for (String time : new String[]{"2026-11-23T11:59:59Z", "2026-11-23T14:28:00Z", "2026-11-23T14:29:59.999Z", "2026-11-23T14:30:00Z", "2026-11-24T01:00:00Z", "2026-11-22T13:00:00Z"})
            assertFalse(MarketClock.isExtendedTradingTime(Instant.parse(time).toEpochMilli()), time);
        assertTrue(MarketClock.isRegularTradingTime(Instant.parse("2026-11-23T14:30:00Z").toEpochMilli()));
        assertFalse(MarketClock.isRegularTradingTime(Instant.parse("2026-11-23T21:00:00Z").toEpochMilli()));
    }
    @Test void regularOrdersStartAt928EasternWithoutMovingTheActualSessionOpen() {
        // Winter and summer UTC offsets must select the same Eastern boundaries.
        for (String day : new String[]{"2026-11-23T14:", "2026-07-06T13:"}) {
            assertFalse(MarketClock.isRegularOrderTime(Instant.parse(day + "27:59.999Z").toEpochMilli()));
            for (String minute : new String[]{"28:00Z", "29:59.999Z"}) {
                long time = Instant.parse(day + minute).toEpochMilli();
                assertTrue(MarketClock.isRegularOrderTime(time));
                assertFalse(MarketClock.isExtendedTradingTime(time));
                assertFalse(MarketClock.isRegularTradingTime(time));
                assertTrue(MarketClock.marketTime(time).isPremarket);
                assertFalse(MarketClock.marketTime(time).isRegularSession);
            }
        }
        assertFalse(MarketClock.isRegularOrderTime(Instant.parse("2026-11-22T14:28:00Z").toEpochMilli()));
        assertFalse(MarketClock.isRegularOrderTime(Instant.parse("2026-11-23T21:00:00Z").toEpochMilli()));
    }
}
