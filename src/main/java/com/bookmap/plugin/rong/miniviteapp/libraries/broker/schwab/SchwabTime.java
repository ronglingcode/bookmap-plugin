package com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

/** Parses the ISO timestamps returned in Schwab execution legs. */
public final class SchwabTime {
    private static final DateTimeFormatter COMPACT_OFFSET = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .appendOffset("+HHMM", "Z")
            .toFormatter();

    private SchwabTime() { }

    public static long executionMillis(String value) {
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (RuntimeException standardFormatError) {
            return OffsetDateTime.parse(value, COMPACT_OFFSET).toInstant().toEpochMilli();
        }
    }
}
