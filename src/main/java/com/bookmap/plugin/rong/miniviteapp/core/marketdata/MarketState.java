package com.bookmap.plugin.rong.miniviteapp.core.marketdata;

import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import com.bookmap.plugin.rong.miniviteapp.models.Trade;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/** Per-symbol market state based on TS MarketState, with Bookmap session levels. */
public final class MarketState {
    private final String symbol, date;
    private final double marketCap;
    private final TreeMap<Long, Bucket> buckets = new TreeMap<>();
    private final TreeMap<Long, Double> vwaps = new TreeMap<>();
    private final Set<String> seen = new HashSet<>();
    private long liveFrom, latestPriceTime;
    private Long firstRegularBucketTime;
    private double totalVolume, totalDollars, premarketDollars, currentPrice, highOfDay, lowOfDay, premarketHigh, premarketLow, liquidityScale;
    private boolean lockedAtMax;
    private boolean corrected;
    private double correctionVolume, correctionDollars;
    public MarketState(String symbol, String date, double marketCapInMillions) { this.symbol = symbol; this.date = date; this.marketCap = marketCapInMillions; }
    private static final class Bucket {
        final long datetime;
        long firstTradeTime, lastTradeTime;
        double open, high, low, close, volume, vwap, dollars;
        Bucket(Candle candle, long first, long last, double dollars) {
            datetime = candle.datetime; open = candle.open; high = candle.high; low = candle.low; close = candle.close;
            volume = candle.volume; vwap = candle.vwap; firstTradeTime = first; lastTradeTime = last; this.dollars = dollars;
        }
        Candle candle(String symbol) { return new Candle(symbol, datetime, open, high, low, close, volume, vwap); }
    }
    public synchronized void initialize(List<Candle> history, long liveFrom, double correctionVolume, double correctionDollars) {
        buckets.clear(); seen.clear(); vwaps.clear(); this.liveFrom = liveFrom;
        totalVolume = totalDollars = premarketDollars = currentPrice = highOfDay = lowOfDay = premarketHigh = premarketLow = liquidityScale = 0;
        latestPriceTime = 0; firstRegularBucketTime = null; lockedAtMax = false; corrected = false;
        this.correctionVolume = correctionVolume; this.correctionDollars = correctionDollars;
        TreeMap<Long, Candle> bars = new TreeMap<>(); history.forEach(candle -> bars.put(candle.datetime, candle));
        for (Candle candle : bars.values()) {
            MarketClock.Time time = MarketClock.marketTime(candle.datetime);
            if (!time.date.equals(date) || time.minutesSinceMarketOpen < -510 || candle.datetime + 60000 > liveFrom) continue;
            applyCorrection(time.minutesSinceMarketOpen);
            double dollars = candle.volume * PremarketVolume.typicalPrice(candle);
            buckets.put(candle.datetime, new Bucket(candle, candle.datetime, candle.datetime + 59999, dollars));
            if (time.minutesSinceMarketOpen >= 0 && time.minutesSinceMarketOpen < 390
                    && firstRegularBucketTime == null) firstRegularBucketTime = candle.datetime;
            totalVolume += candle.volume; totalDollars += dollars; if (time.isPremarket) premarketDollars += dollars;
            updateLevels(candle.high, candle.low, time);
            currentPrice = candle.close; latestPriceTime = candle.datetime + 59999;
            vwaps.put(candle.datetime, totalVolume > 0 ? totalDollars / totalVolume : 0);
        }
        updateLiquidity();
    }
    public synchronized boolean applyTrade(Trade trade) {
        MarketClock.Time time = MarketClock.marketTime(trade.timestamp); long bucketTime = trade.timestamp / 60000 * 60000;
        if (!trade.symbol.equals(symbol) || !time.date.equals(date) || time.minutesSinceMarketOpen < -510 || trade.timestamp < liveFrom || trade.price <= 0 || trade.size <= 0) return false;
        if (!buckets.isEmpty() && bucketTime < buckets.lastKey()) return false;
        String key = trade.sequence != null ? "q:" + trade.sequence : trade.id == null ? "" : "i:" + (trade.exchange == null ? "" : trade.exchange) + ":" + trade.id;
        if (buckets.isEmpty() || bucketTime > buckets.lastKey()) seen.clear();
        if (!key.isEmpty() && !seen.add(key)) return false;
        Bucket candle = buckets.get(bucketTime);
        applyCorrection(time.minutesSinceMarketOpen);
        if (candle == null) {
            candle = new Bucket(new Candle(symbol, bucketTime, trade.price, trade.price, trade.price, trade.price, 0, 0), trade.timestamp, trade.timestamp, 0);
            buckets.put(bucketTime, candle);
            if (time.minutesSinceMarketOpen >= 0 && time.minutesSinceMarketOpen < 390
                    && firstRegularBucketTime == null) firstRegularBucketTime = bucketTime;
        }
        if (trade.timestamp < candle.firstTradeTime) { candle.open = trade.price; candle.firstTradeTime = trade.timestamp; }
        if (trade.timestamp >= candle.lastTradeTime) { candle.close = trade.price; candle.lastTradeTime = trade.timestamp; }
        candle.high = Math.max(candle.high, trade.price); candle.low = Math.min(candle.low, trade.price);
        candle.volume += trade.size; candle.dollars += trade.price * trade.size; candle.vwap = candle.dollars / candle.volume;
        totalVolume += trade.size; totalDollars += trade.price * trade.size; if (time.isPremarket) premarketDollars += trade.price * trade.size;
        updateLevels(trade.price, trade.price, time);
        if (trade.timestamp >= latestPriceTime) { currentPrice = trade.price; latestPriceTime = trade.timestamp; }
        vwaps.put(bucketTime, totalDollars / totalVolume); updateLiquidity(); return true;
    }
    /** Bookmap prints extend session levels without duplicating Massive volume or candles. */
    public synchronized boolean applyBookmapLevels(String sessionDate, double newHighOfDay, double newLowOfDay) {
        if (!date.equals(sessionDate) || !Double.isFinite(newHighOfDay) || !Double.isFinite(newLowOfDay)
                || newHighOfDay <= 0 || newLowOfDay <= 0 || newHighOfDay < newLowOfDay) return false;
        double previousHigh = highOfDay, previousLow = lowOfDay;
        updateRegularLevels(newHighOfDay, newLowOfDay);
        return highOfDay != previousHigh || lowOfDay != previousLow;
    }
    private void applyCorrection(double minutesSinceMarketOpen) {
        if (!corrected && minutesSinceMarketOpen >= -30 && minutesSinceMarketOpen < 0 && correctionVolume > 0 && correctionDollars > 0) {
            totalVolume = correctionVolume; totalDollars = premarketDollars = correctionDollars; corrected = true;
        }
    }
    private void updateLevels(double high, double low, MarketClock.Time time) {
        high = Math.ceil(high * 100) / 100; low = Math.floor(low * 100) / 100;
        if (time.isPremarket) { premarketHigh = Math.max(premarketHigh, high); premarketLow = premarketLow != 0 ? Math.min(premarketLow, low) : low; }
        else if (time.minutesSinceMarketOpen < 390) updateRegularLevels(high, low);
    }
    private void updateRegularLevels(double high, double low) {
        high = Math.ceil(high * 100) / 100; low = Math.floor(low * 100) / 100;
        highOfDay = Math.max(highOfDay, high); lowOfDay = lowOfDay != 0 ? Math.min(lowOfDay, low) : low;
    }
    private void updateLiquidity() {
        if (lockedAtMax) { liquidityScale = 1; return; }
        List<Double> regular = new ArrayList<>(); double premarketVolume = 0;
        for (Bucket candle : buckets.values()) {
            if (MarketClock.marketTime(candle.datetime).isPremarket) premarketVolume = candle.volume; else regular.add(candle.volume);
        }
        liquidityScale = Liquidity.calculateLiquidityScale(currentPrice, regular, premarketVolume, marketCap, lockedAtMax);
        if (liquidityScale == 1) lockedAtMax = true;
    }
    public synchronized JsonObject metrics() {
        JsonObject json = new JsonObject();
        Bucket latest = buckets.isEmpty() ? null : buckets.lastEntry().getValue();
        json.addProperty("currentPrice", currentPrice); json.addProperty("vwap", totalVolume != 0 ? totalDollars / totalVolume : 0);
        json.addProperty("totalVolume", totalVolume); json.addProperty("totalTradingAmount", totalDollars);
        json.addProperty("premarketDollarTraded", premarketDollars); json.addProperty("highOfDay", highOfDay); json.addProperty("lowOfDay", lowOfDay);
        json.addProperty("premarketHigh", premarketHigh); json.addProperty("premarketLow", premarketLow);
        json.addProperty("openPrice", firstRegularBucketTime == null ? currentPrice : buckets.get(firstRegularBucketTime).open);
        json.addProperty("liquidityScale", liquidityScale); json.addProperty("liquidityScaleLockedAtMax", lockedAtMax);
        if (latest != null) json.add("candle", latest.candle(symbol).toJson());
        json.addProperty("firstTradeTime", latest == null ? 0 : latest.firstTradeTime); json.addProperty("latestPriceTime", latestPriceTime);
        if (latest != null) { var closed = vwaps.lowerEntry(latest.datetime); if (closed != null) { JsonObject point = new JsonObject(); point.addProperty("datetime", closed.getKey()); point.addProperty("value", closed.getValue()); json.add("closedVwap", point); } }
        return json;
    }
    /** Small session view shared with Bookmap's display and order inputs. */
    public synchronized JsonObject sessionLevels() {
        JsonObject json = new JsonObject();
        json.addProperty("sessionDate", date);
        json.addProperty("highOfDay", highOfDay);
        json.addProperty("lowOfDay", lowOfDay);
        if (firstRegularBucketTime != null
                && MarketClock.marketTime(firstRegularBucketTime).minutesSinceMarketOpen < 1
                && Double.isFinite(buckets.get(firstRegularBucketTime).open)
                && buckets.get(firstRegularBucketTime).open > 0) {
            json.addProperty("openPrice", buckets.get(firstRegularBucketTime).open);
        }
        json.addProperty("timestamp", latestPriceTime);
        return json;
    }
    /** Entry decisions require the actual opening minute, never a current-price fallback. */
    public synchronized JsonObject orderSnapshot() {
        JsonObject json = snapshot();
        JsonObject levels = sessionLevels();
        json.addProperty("openPrice", levels.has("openPrice") ? levels.get("openPrice").getAsDouble() : 0);
        return json;
    }
    public synchronized JsonObject snapshot() {
        JsonObject json = new JsonObject(); json.addProperty("symbol", symbol); json.addProperty("date", date);
        JsonArray candles = new JsonArray(), series = new JsonArray(); double openPrice = currentPrice; boolean foundOpen = false;
        for (Bucket candle : buckets.values()) {
            candles.add(candle.candle(symbol).toJson());
            if (!foundOpen && !MarketClock.marketTime(candle.datetime).isPremarket) { openPrice = candle.open; foundOpen = true; }
        }
        vwaps.forEach((datetime, value) -> { JsonObject point = new JsonObject(); point.addProperty("datetime", datetime); point.addProperty("value", value); series.add(point); });
        json.add("candles", candles); json.add("vwaps", series); json.addProperty("currentPrice", currentPrice);
        json.addProperty("vwap", totalVolume != 0 ? totalDollars / totalVolume : 0); json.addProperty("totalVolume", totalVolume);
        json.addProperty("totalTradingAmount", totalDollars); json.addProperty("premarketDollarTraded", premarketDollars);
        json.addProperty("highOfDay", highOfDay); json.addProperty("lowOfDay", lowOfDay); json.addProperty("premarketHigh", premarketHigh); json.addProperty("premarketLow", premarketLow);
        json.addProperty("openPrice", openPrice); json.addProperty("liquidityScale", liquidityScale); json.addProperty("liquidityScaleLockedAtMax", lockedAtMax); return json;
    }
}
