package com.bookmap.plugin.rong.miniviteapp.runtime;

import com.bookmap.plugin.rong.miniviteapp.core.marketdata.MarketState;
import com.bookmap.plugin.rong.miniviteapp.core.marketdata.MarketClock;
import com.bookmap.plugin.rong.miniviteapp.libraries.massive.Api;
import com.bookmap.plugin.rong.miniviteapp.libraries.massive.Mapper;
import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import com.bookmap.plugin.rong.miniviteapp.models.Trade;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/** Loads Massive history and retains Bookmap session extremes during asynchronous reads. */
public final class MarketLoader implements AutoCloseable {
    private final Api api;
    private final Executor executor;
    private final LongSupplier now;
    private final Map<String, MarketState> states = new HashMap<>();
    private final Map<String, Loading> loading = new HashMap<>();
    private final Map<String, BookmapLevels> bookmapLevels = new HashMap<>();
    private boolean closed;
    public MarketLoader(Api api, Executor executor, LongSupplier now) { this.api = api; this.executor = executor; this.now = now; }
    public static final class Loaded {
        public final MarketState state;
        public final JsonObject history;
        public final List<Candle> todayBars;
        private Loaded(MarketState state, JsonObject history, List<Candle> todayBars) {
            this.state = state; this.history = history; this.todayBars = todayBars;
        }
    }
    private static final class Loading {
        final List<Trade> buffer = new ArrayList<>();
        final CompletableFuture<Loaded> promise = new CompletableFuture<>();
    }
    private static final class BookmapLevels {
        final String date;
        double highOfDay, lowOfDay;
        BookmapLevels(String date, double price) { this.date = date; highOfDay = lowOfDay = price; }
        void accept(double price) { highOfDay = Math.max(highOfDay, price); lowOfDay = Math.min(lowOfDay, price); }
    }
    public synchronized MarketState getState(String symbol) { return states.get(symbol); }
    public synchronized void forget(String symbol) { states.remove(symbol); loading.remove(symbol); bookmapLevels.remove(symbol); }
    public synchronized boolean acceptBookmapTrade(String symbol, double price, long timestampMs) {
        if (closed || symbol == null || symbol.isEmpty() || !Double.isFinite(price) || price <= 0 || timestampMs <= 0) return false;
        MarketClock.Time time = MarketClock.marketTime(timestampMs);
        if (time.minutesSinceMarketOpen < 0 || time.minutesSinceMarketOpen >= 390) return false;
        BookmapLevels levels = bookmapLevels.get(symbol);
        if (levels != null && time.date.compareTo(levels.date) < 0) return false;
        if (levels == null || !levels.date.equals(time.date)) bookmapLevels.put(symbol, levels = new BookmapLevels(time.date, price));
        else levels.accept(price);
        MarketState state = states.get(symbol);
        return state != null && state.applyBookmapLevels(time.date, price, price);
    }
    public synchronized boolean acceptTrade(Trade trade) {
        if (closed || Mapper.shouldFilterTrade(trade)) return false;
        Loading pending = loading.get(trade.symbol); if (pending != null) pending.buffer.add(trade);
        MarketState state = states.get(trade.symbol); return state != null && state.applyTrade(trade);
    }
    public synchronized CompletableFuture<Loaded> load(String symbol, String date, double marketCap, double correctionVolume, double correctionDollars) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Market loader stopped"));
        if (loading.containsKey(symbol)) return loading.get(symbol).promise;
        Loading pending = new Loading(); loading.put(symbol, pending); long liveFrom = now.getAsLong() / 60000 * 60000;
        try { executor.execute(() -> {
            try {
                Loaded result = performLoad(symbol, date, marketCap, correctionVolume, correctionDollars, liveFrom, pending);
                synchronized (this) { loading.remove(symbol, pending); }
                pending.promise.complete(result);
            } catch (Exception error) {
                synchronized (this) { loading.remove(symbol, pending); }
                pending.promise.completeExceptionally(error);
            }
        }); } catch (RuntimeException error) { loading.remove(symbol); pending.promise.completeExceptionally(error); }
        return pending.promise;
    }
    private Loaded performLoad(String symbol, String date, double marketCap, double correctionVolume, double correctionDollars, long liveFrom, Loading pending) throws Exception {
        JsonObject history = api.getFullPriceHistory(symbol, date); List<Trade> backfill = api.getTrades(symbol, liveFrom, now.getAsLong());
        synchronized (this) {
            if (closed || loading.get(symbol) != pending) throw new IllegalStateException("Market load replaced or stopped");
            List<Candle> todayBars = candles(history.getAsJsonArray("today1MinuteBars"));
            MarketState state = new MarketState(symbol, date, marketCap); state.initialize(todayBars, liveFrom, correctionVolume, correctionDollars);
            List<Trade> prints = new ArrayList<>(backfill); prints.addAll(pending.buffer); prints.sort(Comparator.comparingLong(trade -> trade.timestamp));
            for (Trade trade : prints) if (!Mapper.shouldFilterTrade(trade)) state.applyTrade(trade);
            BookmapLevels levels = bookmapLevels.get(symbol);
            if (levels != null) state.applyBookmapLevels(levels.date, levels.highOfDay, levels.lowOfDay);
            states.put(symbol, state); return new Loaded(state, history, todayBars);
        }
    }
    private static List<Candle> candles(JsonArray values) {
        List<Candle> result = new ArrayList<>();
        for (JsonElement value : values) {
            JsonObject c = value.getAsJsonObject(); result.add(new Candle(c.get("symbol").getAsString(), c.get("datetime").getAsLong(), c.get("open").getAsDouble(), c.get("high").getAsDouble(), c.get("low").getAsDouble(), c.get("close").getAsDouble(), c.get("volume").getAsDouble(), c.get("vwap").getAsDouble()));
        }
        return result;
    }
    @Override public synchronized void close() { closed = true; states.clear(); loading.clear(); bookmapLevels.clear(); }
}
