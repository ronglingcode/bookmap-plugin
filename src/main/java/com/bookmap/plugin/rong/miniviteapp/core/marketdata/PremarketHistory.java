package com.bookmap.plugin.rong.miniviteapp.core.marketdata;

import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Schwab owns each returned minute; Massive supplies only absent minutes. */
public final class PremarketHistory {
    private static final long MINUTE = 60000;
    public interface Bars { List<Candle> load(String symbol, long from, long toInclusive) throws Exception; }
    public static final class Result {
        public final List<Candle> candles;
        public final int backfilledMinutes;
        public final double schwabShares, massiveShares;
        private Result(TreeMap<Long, Candle> candles, int backfilledMinutes, double schwabShares, double massiveShares) {
            this.candles = new ArrayList<>(candles.values()); this.backfilledMinutes = backfilledMinutes;
            this.schwabShares = schwabShares; this.massiveShares = massiveShares;
        }
    }
    private PremarketHistory() { }
    public static Result load(String symbol, long from, long toInclusive, Bars schwab, Bars massive) throws Exception {
        if (from > toInclusive) throw new IllegalArgumentException("Invalid premarket range");
        TreeMap<Long, Candle> candles = new TreeMap<>();
        for (Candle candle : schwab.load(symbol, from, toInclusive)) {
            if (inRange(candle, from, toInclusive)) { validate(candle); candles.put(candle.datetime, candle); }
        }
        double schwabShares = candles.values().stream().mapToDouble(candle -> candle.volume).sum();
        boolean missing = false;
        for (long minute = from; minute <= toInclusive; minute += MINUTE) {
            if (!candles.containsKey(minute)) { missing = true; break; }
        }
        int backfilled = 0; double massiveShares = 0;
        if (missing) {
            // One bounded request also checks internal gaps. Both feeds may omit quiet minutes.
            for (Candle candle : massive.load(symbol, from, toInclusive)) {
                if (!inRange(candle, from, toInclusive) || candles.containsKey(candle.datetime)) continue;
                validate(candle); candles.put(candle.datetime, candle);
                backfilled++; massiveShares += candle.volume;
            }
        }
        if (candles.isEmpty()) throw new IOException("No premarket candles from Schwab or Massive");
        return new Result(candles, backfilled, schwabShares, massiveShares);
    }
    private static boolean inRange(Candle candle, long from, long toInclusive) {
        return candle.datetime >= from && candle.datetime <= toInclusive;
    }
    private static void validate(Candle candle) throws IOException {
        if (candle.datetime % MINUTE != 0 || !Double.isFinite(candle.volume) || candle.volume < 0)
            throw new IOException("Invalid premarket minute candle");
    }
}
