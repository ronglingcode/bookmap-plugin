package com.bookmap.plugin.rong;

import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Per-chart premarket share policy. Network work never runs on Bookmap callbacks. */
public final class PremarketSizeThreshold implements AutoCloseable {
    static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    public static final int FALLBACK = 5000;
    public interface Bars { List<Candle> load(String symbol, long from, long to) throws Exception; }
    private final String symbol;
    private final LongSupplier marketTime, now;
    private final Bars bars;
    private final ScheduledExecutorService worker;
    private volatile LocalDate date;
    private volatile double volume = Double.NaN;
    private boolean finalized;
    private volatile String unavailableReason = "waiting for history";

    public PremarketSizeThreshold(String symbol, LongSupplier marketTime, Bars bars) {
        this(symbol, marketTime, System::currentTimeMillis, bars);
    }
    PremarketSizeThreshold(String symbol, LongSupplier marketTime, LongSupplier now, Bars bars) {
        this.symbol = symbol; this.marketTime = marketTime; this.now = now; this.bars = bars;
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "premarket-threshold-" + symbol); thread.setDaemon(true); return thread;
        });
    }
    public void start() { worker.scheduleWithFixedDelay(this::refresh, 0, 60, TimeUnit.SECONDS); }
    private boolean currentMarket() {
        long time = marketTime.getAsLong();
        return time > 0 && Instant.ofEpochMilli(time).atZone(NEW_YORK).toLocalDate()
                .equals(Instant.ofEpochMilli(now.getAsLong()).atZone(NEW_YORK).toLocalDate());
    }
    void refresh() {
        if (!currentMarket()) { unavailableReason = "chart session is historical or unknown"; return; }
        ZonedDateTime time = Instant.ofEpochMilli(now.getAsLong()).atZone(NEW_YORK);
        LocalDate today = time.toLocalDate();
        if (!today.equals(date)) { volume = Double.NaN; date = today; finalized = false; }
        long start = today.atTime(4, 0).atZone(NEW_YORK).toInstant().toEpochMilli();
        long open = today.atTime(9, 30).atZone(NEW_YORK).toInstant().toEpochMilli();
        if (finalized || time.toInstant().toEpochMilli() <= start) return;
        long end = Math.min(Math.min(now.getAsLong(), marketTime.getAsLong()), open);
        if (end <= start) return;
        try {
            double total = sum(bars.load(symbol, start, end - 1), start, end);
            if (!Double.isFinite(total)) { volume = Double.NaN; unavailableReason = "invalid aggregate volume"; return; }
            volume = total; finalized = end == open;
            unavailableReason = "";
            PluginLog.detail(symbol, "Premarket threshold: Schwab 1-minute history shares=" + total + "; size=" + forVolume(total));
        } catch (Exception unavailable) {
            volume = Double.NaN;
            unavailableReason = "history fetch failed (" + unavailable.getClass().getSimpleName() + ")";
            PluginLog.detail(symbol, "Premarket threshold: " + unavailableReason);
        }
    }
    static double sum(List<Candle> candles, long start, long end) {
        double total = 0;
        for (Candle candle : candles) if (candle.datetime >= start && candle.datetime < end) {
            if (!Double.isFinite(candle.volume) || candle.volume < 0) return Double.NaN;
            total += candle.volume;
        }
        return total;
    }
    public double volume() {
        LocalDate today = Instant.ofEpochMilli(now.getAsLong()).atZone(NEW_YORK).toLocalDate();
        if (!currentMarket()) return Double.NaN;
        return today.equals(date) ? volume : Double.NaN;
    }
    public String unavailableReason() { return currentMarket() ? unavailableReason : "chart session is historical or unknown"; }
    public int threshold() { return forVolume(volume()); }
    public static int forVolume(double shares) {
        if (!Double.isFinite(shares) || shares < 0) return FALLBACK;
        if (shares > 4000000) return 20000;
        if (shares > 3000000) return 15000;
        if (shares > 2000000) return 10000;
        return shares >= 1000000 ? 5000 : 2000;
    }
    @Override public void close() { worker.shutdownNow(); }
}
