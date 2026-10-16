package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.marketdata.PremarketHistory;
import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PremarketHistoryTest {
    static final long START = Instant.parse("2026-10-16T05:00:00Z").toEpochMilli();
    static final long MINUTE = 60000, END = START + 4 * MINUTE - 1;
    static Candle bar(long time, double volume) { return new Candle("TSLA", time, 10, 10, 10, 10, volume, 10); }

    @Test void fillsLeadingInternalAndTrailingGapsWithoutReplacingSchwabOrDoubleCounting() throws Exception {
        Candle primary = bar(START + MINUTE, 200);
        var result = PremarketHistory.load("TSLA", START, END,
                (symbol, from, to) -> List.of(primary, primary, bar(START - MINUTE, 999999), bar(END + 1, 999999)),
                (symbol, from, to) -> {
                    assertEquals("TSLA", symbol); assertEquals(START, from); assertEquals(END, to);
                    return List.of(bar(START + 3 * MINUTE, 40), bar(START, 10), bar(START, 10),
                            bar(START + MINUTE, 999999), bar(START + 2 * MINUTE, 30), bar(END + 1, 999999));
                });
        assertEquals(4, result.candles.size()); assertEquals(3, result.backfilledMinutes);
        assertEquals(200, result.schwabShares); assertEquals(80, result.massiveShares);
        assertSame(primary, result.candles.get(1));
        assertEquals(START, result.candles.get(0).datetime);
        assertEquals(START + 3 * MINUTE, result.candles.get(3).datetime);
    }
    @Test void completeSchwabHistoryIncludingZeroVolumeDoesNotQueryMassive() throws Exception {
        var result = PremarketHistory.load("TSLA", START, START + MINUTE - 1,
                (symbol, from, to) -> List.of(bar(START, 0)),
                (symbol, from, to) -> { fail("Complete Schwab history must not fetch Massive"); return List.of(); });
        assertEquals(0, result.backfilledMinutes); assertEquals(0, result.schwabShares);
    }
    @Test void emptySchwabHistoryCanBeBackfilledAndQuietMinutesStayAbsent() throws Exception {
        var result = PremarketHistory.load("TSLA", START, END, (symbol, from, to) -> List.of(),
                (symbol, from, to) -> List.of(bar(START + MINUTE, 123.5)));
        assertEquals(1, result.candles.size()); assertEquals(123.5, result.massiveShares);
    }
    @Test void emptyBothFeedsIsUnavailable() {
        assertThrows(IOException.class, () -> PremarketHistory.load("TSLA", START, END,
                (symbol, from, to) -> List.of(), (symbol, from, to) -> List.of()));
    }
    @Test void failedBackfillDoesNotPublishPartialSchwabTotal() {
        assertThrows(IOException.class, () -> PremarketHistory.load("TSLA", START, END,
                (symbol, from, to) -> List.of(bar(START, 100)),
                (symbol, from, to) -> { throw new IOException("offline"); }));
    }
    @Test void schwabFailureIsNotSilentlyReplacedWithAnotherProvider() {
        assertThrows(IOException.class, () -> PremarketHistory.load("TSLA", START, END,
                (symbol, from, to) -> { throw new IOException("unauthorized"); },
                (symbol, from, to) -> { fail("Schwab errors retain fallback behavior"); return List.of(); }));
    }
    @Test void invalidBackfillVolumeIsUnavailable() {
        for (double invalid : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IOException.class, () -> PremarketHistory.load("TSLA", START, END,
                    (symbol, from, to) -> List.of(bar(START, 100)),
                    (symbol, from, to) -> List.of(bar(START + MINUTE, invalid))));
        }
    }
}
