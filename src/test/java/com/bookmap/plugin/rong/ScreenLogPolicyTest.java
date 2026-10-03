package com.bookmap.plugin.rong;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ScreenLogPolicyTest {
    @TempDir Path directory;

    private PluginLogEvent event(String message, String summary) {
        return new PluginLogEvent(OffsetDateTime.parse("2026-10-02T13:30:00-07:00"), "session", "AAPL", "Native", message, summary);
    }

    @Test void hiddenAndRepeatedEventsStillPersistInFull() throws Exception {
        ScreenLogPolicy policy = new ScreenLogPolicy();
        PluginLogEvent detail = event("Native broker execution time sample: raw=2026-10-02T13:30:00Z", null);
        PluginLogEvent summary = event("actionId=abc planned=2 attempted=2 acknowledged=2", "Cancel: broker acknowledged 2 requests; final state unconfirmed");
        PluginLogEvent warning = event("Account refresh failed: HTTP 503", "Account refresh failed: HTTP 503");
        try (LocalLogWriter writer = new LocalLogWriter(directory, status -> {}, status -> {})) {
            for (PluginLogEvent event : new PluginLogEvent[]{detail, summary, warning, warning}) assertTrue(writer.append(event));
            assertNull(policy.line(detail, 0));
            String line = policy.line(summary, 0);
            assertTrue(line.contains("Cancel: broker acknowledged 2 requests"));
            assertFalse(line.contains("actionId"));
            assertNotNull(policy.line(warning, 0));
            assertNull(policy.line(warning, TimeUnit.SECONDS.toNanos(1)));
        }
        String text = Files.readString(directory.resolve("bmtrader-2026-10-02.log"));
        assertTrue(text.contains("execution time sample: raw="));
        assertTrue(text.contains("actionId=abc planned=2 attempted=2 acknowledged=2"));
        assertEquals(2, text.lines().filter(line -> line.contains("Account refresh failed")).count());
    }

    @Test void repeatCountsReturnAfterIntervalAndOtherSymbolsRemainVisible() {
        ScreenLogPolicy policy = new ScreenLogPolicy();
        PluginLogEvent warning = event("Account refresh failed", "Account refresh failed");
        assertNotNull(policy.line(warning, 0));
        assertNull(policy.line(warning, TimeUnit.SECONDS.toNanos(1)));
        assertNull(policy.line(warning, TimeUnit.SECONDS.toNanos(2)));
        PluginLogEvent other = new PluginLogEvent(warning.timestamp, "session", "MSFT", "Native", warning.message);
        assertNotNull(policy.line(other, TimeUnit.SECONDS.toNanos(2)));
        assertTrue(policy.line(warning, TimeUnit.SECONDS.toNanos(30)).contains("2 repeats since last shown"));
        policy.clear();
        assertNotNull(policy.line(warning, TimeUnit.SECONDS.toNanos(31)));
    }

    @Test void identicalSummariesOfSeparateTradingActionsRemainVisible() {
        ScreenLogPolicy policy = new ScreenLogPolicy();
        assertNotNull(policy.line(event("actionId=first acknowledged=1", "Entry acknowledged; final state unconfirmed"), 0));
        assertNotNull(policy.line(event("actionId=second acknowledged=1", "Entry acknowledged; final state unconfirmed"), 1));
    }

    @Test void quickConnectionRecoveryIsNotSuppressed() {
        ScreenLogPolicy policy = new ScreenLogPolicy();
        PluginLogEvent connected = event("schwab: connected", "schwab: connected");
        assertNotNull(policy.line(connected, 0, false));
        assertNotNull(policy.line(event("schwab: reconnecting", "schwab: reconnecting"), 1, false));
        assertNotNull(policy.line(connected, 2, false));
    }

    @Test void longScreenMessagesAreBoundedAndBothSinksRedactSummaryCredentials() {
        ScreenLogPolicy policy = new ScreenLogPolicy();
        PluginLogEvent event = event("full " + "x".repeat(1000) + " access_token=secret", "Outcome unknown; review broker orders " + "x".repeat(1000) + " access_token=secret");
        String line = policy.line(event, 0);
        assertTrue(line.contains("review broker orders"));
        assertTrue(line.endsWith("details in log file)"));
        assertTrue(line.length() < 280);
        assertFalse(event.screenLine().contains("secret"));
        assertFalse(event.fileLine().contains("secret"));
        assertTrue(event.fileLine().contains("x".repeat(1000)));
    }
}
