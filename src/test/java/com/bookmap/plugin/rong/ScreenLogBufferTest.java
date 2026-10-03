package com.bookmap.plugin.rong;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScreenLogBufferTest {
    private long seconds(long value) { return TimeUnit.SECONDS.toNanos(value); }

    @Test void tenChangingFillUpdatesRemainOneSummaryWithTheLatestCount() {
        ScreenLogBuffer buffer = new ScreenLogBuffer(20);
        for (int leg = 1; leg <= 10; leg++) {
            buffer.append("Fills available: " + leg, "account-fills:2026-10-02", seconds(leg * 3));
        }
        assertEquals(List.of("Fills available: 10"), buffer.lines());
    }

    @Test void warningsAndSeparateUserActionsAreRetainedDuringFillBursts() {
        ScreenLogBuffer buffer = new ScreenLogBuffer(20);
        buffer.append("First action acknowledged", null, 0);
        buffer.append("Fills available: 1", "fills", seconds(1));
        buffer.append("Order rejected: review broker", null, seconds(2));
        buffer.append("Second action acknowledged", null, seconds(3));
        buffer.append("Fills available: 10", "fills", seconds(4));
        assertEquals(List.of("First action acknowledged", "Order rejected: review broker", "Second action acknowledged", "Fills available: 10"), buffer.lines());
    }

    @Test void laterBurstsAndDifferentGroupsHaveSeparateEntries() {
        ScreenLogBuffer buffer = new ScreenLogBuffer(20);
        buffer.append("AAPL fills: 1", "AAPL", 0);
        buffer.append("MSFT fills: 1", "MSFT", seconds(1));
        buffer.append("AAPL fills: 10", "AAPL", seconds(2));
        buffer.append("AAPL fills: 11", "AAPL", seconds(10));
        assertEquals(List.of("MSFT fills: 1", "AAPL fills: 10", "AAPL fills: 11"), buffer.lines());
    }

    @Test void historyRemainsBoundedAndClearsBetweenSessions() {
        ScreenLogBuffer buffer = new ScreenLogBuffer(2);
        buffer.append("old action", null, 0);
        buffer.append("fills: 1", "fills", seconds(1));
        buffer.append("new action", null, seconds(2));
        buffer.append("fills: 10", "fills", seconds(3));
        assertEquals(List.of("new action", "fills: 10"), buffer.lines());
        buffer.clear();
        assertTrue(buffer.lines().isEmpty());
    }
}
