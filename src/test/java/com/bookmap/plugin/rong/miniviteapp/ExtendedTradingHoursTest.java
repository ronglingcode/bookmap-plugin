package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.marketdata.MarketClock;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedTradingHoursTest {
    @Test void extendedSessionBoundariesExcludeRegularHoursAndWeekends() {
        for (String time : new String[]{"2026-11-23T12:00:00Z", "2026-11-23T14:29:59Z", "2026-11-23T21:00:00Z", "2026-11-24T00:59:59Z"})
            assertTrue(MarketClock.isExtendedTradingTime(Instant.parse(time).toEpochMilli()), time);
        for (String time : new String[]{"2026-11-23T11:59:59Z", "2026-11-23T14:30:00Z", "2026-11-24T01:00:00Z", "2026-11-22T13:00:00Z"})
            assertFalse(MarketClock.isExtendedTradingTime(Instant.parse(time).toEpochMilli()), time);
        assertTrue(MarketClock.isRegularTradingTime(Instant.parse("2026-11-23T14:30:00Z").toEpochMilli()));
        assertFalse(MarketClock.isRegularTradingTime(Instant.parse("2026-11-23T21:00:00Z").toEpochMilli()));
    }
}
