package com.bookmap.plugin.rong.miniviteapp.runtime;

import com.bookmap.plugin.rong.miniviteapp.core.marketdata.MarketState;
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

/** Same orchestration as TS MarketLoader. Vendor reads run on an injected I/O executor. */
public final class MarketLoader implements AutoCloseable {
    private final Api api;
    private final Executor executor;
    private final LongSupplier now;
    private final Map<String, MarketState> states = new HashMap<>();
    private final Map<String, Loading> loading = new HashMap<>();
    private boolean closed;
    public MarketLoader(Api api, Executor executor, LongSupplier now) { this.api = api; this.executor = executor; this.now = now; }
    public static final class Loaded {
        public final MarketState state;
        public final JsonObject history;
        private Loaded(MarketState state, JsonObject history) { this.state = state; this.history = history; }
    }
    private static final class Loading {
        final List<Trade> buffer = new ArrayList<>();
        final CompletableFuture<Loaded> promise = new CompletableFuture<>();
    }
    public synchronized MarketState getState(String symbol) { return states.get(symbol); }
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
                synchronized (this) { loading.remove(symbol); }
                pending.promise.complete(result);
            } catch (Exception error) {
                synchronized (this) { loading.remove(symbol); }
                pending.promise.completeExceptionally(error);
            }
        }); } catch (RuntimeException error) { loading.remove(symbol); pending.promise.completeExceptionally(error); }
        return pending.promise;
    }
    private Loaded performLoad(String symbol, String date, double marketCap, double correctionVolume, double correctionDollars, long liveFrom, Loading pending) throws Exception {
        JsonObject history = api.getFullPriceHistory(symbol, date); List<Trade> backfill = api.getTrades(symbol, liveFrom, now.getAsLong());
        synchronized (this) {
            if (closed) throw new IllegalStateException("Market loader stopped");
            MarketState state = new MarketState(symbol, date, marketCap); state.initialize(candles(history.getAsJsonArray("today1MinuteBars")), liveFrom, correctionVolume, correctionDollars);
            List<Trade> prints = new ArrayList<>(backfill); prints.addAll(pending.buffer); prints.sort(Comparator.comparingLong(trade -> trade.timestamp));
            for (Trade trade : prints) if (!Mapper.shouldFilterTrade(trade)) state.applyTrade(trade);
            states.put(symbol, state); return new Loaded(state, history);
        }
    }
    private static List<Candle> candles(JsonArray values) {
        List<Candle> result = new ArrayList<>();
        for (JsonElement value : values) {
            JsonObject c = value.getAsJsonObject(); result.add(new Candle(c.get("symbol").getAsString(), c.get("datetime").getAsLong(), c.get("open").getAsDouble(), c.get("high").getAsDouble(), c.get("low").getAsDouble(), c.get("close").getAsDouble(), c.get("volume").getAsDouble(), c.get("vwap").getAsDouble()));
        }
        return result;
    }
    @Override public synchronized void close() { closed = true; states.clear(); loading.clear(); }
}
