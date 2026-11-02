package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.marketdata.PremarketHistory;
import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import com.bookmap.plugin.rong.miniviteapp.models.Trade;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PremarketHistoryTest {
    static final long START = Instant.parse("2026-10-16T05:00:00Z").toEpochMilli();
    static final long MINUTE = 60000, END = START + 4 * MINUTE - 1;
    static Candle bar(long time, double volume) { return new Candle("TSLA", time, 10, 10, 10, 10, volume, 10); }
    static Trade trade(long time, double size, Integer... conditions) {
        return new Trade("TSLA", time, 10, size, null, null, 4, List.of(conditions));
    }

    @Test void orclAveragePriceReportsExplainTheObservedDiscrepancy() throws Exception {
        var filtered = PremarketHistory.withoutAveragePriceTrades(
                List.of(bar(START, 701013), bar(START + MINUTE, 231187), bar(START + 2 * MINUTE, 1475853.51371)),
                List.of(trade(START + 54000, 411791, 2, 12), trade(START + 54001, 278173, 2, 12),
                        trade(START + 54002, 2035, 2, 12), trade(START + 54003, 639, 2, 12),
                        trade(START + MINUTE + 45000, 229587, 2, 12),
                        trade(START + 2 * MINUTE, 874, 2, 12)));
        assertEquals(1484954.51371, filtered.stream().mapToDouble(candle -> candle.volume).sum(), 0.000001);
        assertEquals(8375, filtered.get(0).volume);
        assertEquals(1600, filtered.get(1).volume);
    }

    @Test void removesOnlyAveragePriceSharesInTheirOwnMinuteAndPreservesPrices() throws Exception {
        Candle source = bar(START, 100);
        var filtered = PremarketHistory.withoutAveragePriceTrades(List.of(source, bar(START + MINUTE, 200)),
                List.of(trade(START - 1, 999999, 2), trade(START, 25.5, 2, 12),
                        trade(START + MINUTE - 1, 4.25, 2), trade(START + MINUTE, 10, 12),
                        trade(START + MINUTE, 20, 37), trade(START + 2 * MINUTE, 999999, 2)));
        assertEquals(70.25, filtered.get(0).volume);
        assertEquals(200, filtered.get(1).volume);
        assertEquals(100, source.volume);
        assertEquals(source.datetime, filtered.get(0).datetime);
        assertEquals(source.open, filtered.get(0).open); assertEquals(source.high, filtered.get(0).high);
        assertEquals(source.low, filtered.get(0).low); assertEquals(source.close, filtered.get(0).close);
        assertEquals(source.vwap, filtered.get(0).vwap);
    }

    @Test void crossProviderDifferencesCannotCreateNegativeVolumeOrReduceOtherMinutes() throws Exception {
        var filtered = PremarketHistory.withoutAveragePriceTrades(List.of(bar(START, 10), bar(START + MINUTE, 100)),
                List.of(trade(START, 20, 2)));
        assertEquals(0, filtered.get(0).volume); assertEquals(100, filtered.get(1).volume);
        assertThrows(IOException.class, () -> PremarketHistory.withoutAveragePriceTrades(List.of(bar(START, 10)),
                List.of(trade(START, Double.NaN, 2))));
    }

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
