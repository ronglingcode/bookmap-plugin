package com.bookmap.plugin.rong;

import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import java.time.*;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private volatile History history;
    private volatile Projection projection;
    private volatile String unavailableReason = "waiting for history";
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private volatile long requestedMinute = Long.MIN_VALUE;
    private volatile boolean closed;
    private static final class History {
        final LocalDate date;
        final List<Candle> candles;
        final long start, end;
        History(LocalDate date, List<Candle> candles, long start, long end) {
            this.date = date; this.candles = new ArrayList<>(candles); this.start = start; this.end = end;
        }
    }
    private static final class Projection {
        final History history;
        final long end;
        final double volume;
        Projection(History history, long end, double volume) { this.history = history; this.end = end; this.volume = volume; }
    }

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
    /** Coalesce chart-minute/open/session changes without running network work on a callback. */
    public void onMarketTime() {
        long time = marketTime.getAsLong();
        if (time <= 0 || closed) return;
        LocalDate date = Instant.ofEpochMilli(time).atZone(NEW_YORK).toLocalDate();
        long open = date.atTime(9, 30).atZone(NEW_YORK).toInstant().toEpochMilli();
        long minute = Math.min(time, open) / 60000;
        if (minute == requestedMinute) return;
        requestedMinute = minute; queueRefresh();
    }
    private void queueRefresh() {
        if (closed || !refreshQueued.compareAndSet(false, true)) return;
        try { worker.execute(() -> {
            long requested = requestedMinute;
            try { refresh(); } finally {
                refreshQueued.set(false);
                if (requested != requestedMinute) queueRefresh();
            }
        }); } catch (RejectedExecutionException stopped) { refreshQueued.set(false); }
    }
    private LocalDate chartDate() {
        long time = marketTime.getAsLong();
        return time > 0 ? Instant.ofEpochMilli(time).atZone(NEW_YORK).toLocalDate() : null;
    }
    void refresh() {
        LocalDate date = chartDate();
        if (date == null) { unavailableReason = "chart time unknown"; return; }
        long start = date.atTime(1, 0).atZone(NEW_YORK).toInstant().toEpochMilli();
        long open = date.atTime(9, 30).atZone(NEW_YORK).toInstant().toEpochMilli();
        // Minute history may contain a completed candle from later in a replayed minute.
        long end = Math.min(Math.min(now.getAsLong(), marketTime.getAsLong()) / 60000 * 60000, open);
        if (end <= start) return;
        History cached = history;
        if (cached != null && date.equals(cached.date) && cached.end >= end) return;
        try {
            List<Candle> candles = bars.load(symbol, start, end - 1);
            double total = sum(candles, start, end);
            if (!date.equals(chartDate())) return; // A session seek happened during the history request.
            if (!Double.isFinite(total)) { history = null; unavailableReason = "invalid aggregate volume"; return; }
            history = new History(date, candles, start, end);
            unavailableReason = "";
            PluginLog.detail(symbol, "Premarket threshold: Schwab + Massive backfill 1-minute history shares=" + total + "; size=" + forVolume(total));
        } catch (Exception unavailable) {
            if (!date.equals(chartDate())) return;
            history = null;
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
        History cached = history;
        if (cached == null || !cached.date.equals(chartDate())) return Double.NaN;
        long end = Math.min(cached.end, Math.min(now.getAsLong(), marketTime.getAsLong()) / 60000 * 60000);
        if (end < cached.start) return Double.NaN;
        Projection value = projection;
        if (value == null || value.history != cached || value.end != end) {
            value = new Projection(cached, end, sum(cached.candles, cached.start, end)); projection = value;
        }
        return value.volume;
    }
    public String unavailableReason() {
        if (chartDate() == null) return "chart time unknown";
        return Double.isFinite(volume()) ? "" : history != null ? "waiting for chart-session history" : unavailableReason;
    }
    public int threshold() { return forVolume(volume()); }
    public static int forVolume(double shares) {
        if (!Double.isFinite(shares) || shares < 0) return FALLBACK;
        if (shares < 1000000) return 2000;
        // Each completed million requires another 5K; saturate only at the API's int limit.
        return (int) Math.min(Integer.MAX_VALUE, Math.floor(shares / 1000000) * 5000);
    }
    @Override public void close() { closed = true; worker.shutdownNow(); }
}
