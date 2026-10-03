package com.bookmap.plugin.rong.miniviteapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.SchwabTime;

class SchwabTimeTest {
    @Test
    void parsesTheOffsetFormsAcceptedByViteAppForSchwabExecutionLegs() {
        long expected = Instant.parse("2026-10-02T13:35:00Z").toEpochMilli();
        assertEquals(expected, SchwabTime.executionMillis("2026-10-02T13:35:00Z"));
        assertEquals(expected, SchwabTime.executionMillis("2026-10-02T13:35:00+00:00"));
        assertEquals(expected, SchwabTime.executionMillis("2026-10-02T13:35:00+0000"));
        assertEquals(expected, SchwabTime.executionMillis("2026-10-02T09:35:00-0400"));
        assertEquals(expected + 123, SchwabTime.executionMillis("2026-10-02T09:35:00.123-0400"));
        assertThrows(RuntimeException.class, () -> SchwabTime.executionMillis("invalid-time"));
    }
}
