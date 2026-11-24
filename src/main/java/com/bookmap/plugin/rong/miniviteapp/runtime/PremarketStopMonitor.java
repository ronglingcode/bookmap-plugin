package com.bookmap.plugin.rong.miniviteapp.runtime;

import com.bookmap.plugin.rong.miniviteapp.core.marketdata.MarketClock;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.*;

/** Local extended-hours stops. No regular-session selection, order, or protection state is shared. */
public final class PremarketStopMonitor {
    private static final class Stop {
        double price, net, lastPrice;
        long selectedAt, entryAt, lastTradeAt;
        int direction;
        boolean held, triggered, exitStarted, entryAccepted;
        String status = "Selected; required for entry";
    }
    private final Map<String, Stop> stops = new HashMap<>();
    private final LongSupplier now;
    private final BooleanSupplier enabled;
    private String session = "";
    public PremarketStopMonitor(LongSupplier now, BooleanSupplier enabled) { this.now = now; this.enabled = enabled; }
    private boolean active() {
        long time = now.getAsLong();
        String key = enabled.getAsBoolean() && MarketClock.isExtendedTradingTime(time)
                ? MarketClock.marketTime(time).date + (MarketClock.marketTime(time).isPremarket ? ":AM" : ":PM") : "";
        if (!key.equals(session)) { stops.clear(); session = key; }
        return !key.isEmpty();
    }
    public synchronized void select(String symbol, double price, double net) {
        if (!active()) throw new IllegalArgumentException("local stops are available during extended hours only");
        price = PremarketTrading.snapChartPrice(price);
        Stop stop = stops.get(symbol);
        if (stop != null && stop.triggered) throw new IllegalArgumentException("stop already triggered; reconcile the exit before selecting another stop");
        if (stop == null) { stop = new Stop(); stops.put(symbol, stop); }
        stop.price = price; stop.selectedAt = now.getAsLong(); stop.net = net;
        if (net != 0) { stop.direction = net > 0 ? 1 : -1; stop.held = true; stop.status = "Monitoring"; }
    }
    public synchronized void requireEntry(String symbol, boolean buy, double entryPrice, double net) {
        if (!active()) throw new IllegalArgumentException("extended-hours session ended");
        Stop stop = stops.get(symbol);
        if (stop == null) throw new IllegalArgumentException("Select premarket stop: hover + Z before entry");
        if (stop.triggered) throw new IllegalArgumentException("local stop has triggered; entry is blocked");
        if (!(buy ? stop.price < entryPrice : stop.price > entryPrice))
            throw new IllegalArgumentException("stop must be below a long entry or above a short entry");
        stop.direction = buy ? 1 : -1; stop.net = net; stop.held |= net != 0;
        if (net == 0 && !stop.entryAccepted) { stop.lastPrice = 0; stop.lastTradeAt = 0; }
        stop.entryAt = now.getAsLong(); stop.status = net == 0 ? "Waiting for entry fill" : "Monitoring";
    }
    public synchronized void position(String symbol, double net, long readStartedAt, boolean workingOrders) {
        if (!active()) return;
        Stop stop = stops.get(symbol);
        if (stop == null || readStartedAt < Math.max(stop.selectedAt, stop.entryAt)) return;
        if (net != 0 && stop.direction != 0 && (net > 0 ? 1 : -1) != stop.direction) { stops.remove(symbol); return; }
        stop.net = net;
        if (net != 0) { stop.held = true; stop.direction = net > 0 ? 1 : -1; if (!stop.triggered) stop.status = "Monitoring"; }
        else if ((stop.held || stop.exitStarted) && !workingOrders) stops.remove(symbol);
        evaluate(stop);
    }
    public synchronized void entryAccepted(String symbol) {
        if (!active()) return;
        Stop stop = stops.get(symbol); if (stop == null) return;
        stop.entryAccepted = true; evaluate(stop);
    }
    public synchronized void trade(String symbol, double price, long timestamp) {
        if (!active() || !Double.isFinite(price) || price <= 0) return;
        long time = now.getAsLong();
        if (timestamp < time - 2000 || timestamp > time + 1000 || !MarketClock.isExtendedTradingTime(timestamp)) return;
        Stop stop = stops.get(symbol);
        if (stop == null || timestamp < Math.max(stop.selectedAt, stop.entryAt) || timestamp < stop.lastTradeAt) return;
        stop.lastPrice = price; stop.lastTradeAt = timestamp; evaluate(stop);
    }
    private void evaluate(Stop stop) {
        if ((stop.net == 0 && !stop.entryAccepted) || stop.direction == 0 || stop.triggered || stop.lastPrice <= 0
                || stop.lastTradeAt < Math.max(stop.selectedAt, stop.entryAt) || now.getAsLong() - stop.lastTradeAt > 2000) return;
        BigDecimal price = BigDecimal.valueOf(stop.lastPrice), level = BigDecimal.valueOf(stop.price), cent = new BigDecimal("0.01");
        if (stop.direction > 0 ? price.compareTo(level.subtract(cent)) <= 0 : price.compareTo(level.add(cent)) >= 0) {
            stop.triggered = true; stop.status = "Triggered; canceling orders and exiting";
        }
    }
    public synchronized boolean triggered(String symbol) { return active() && stops.containsKey(symbol) && stops.get(symbol).triggered; }
    public synchronized boolean exitStarted(String symbol) { return active() && stops.containsKey(symbol) && stops.get(symbol).exitStarted; }
    public synchronized int claimExit(String symbol) {
        if (!active()) return 0;
        Stop stop = stops.get(symbol);
        if (stop == null || !stop.triggered || stop.exitStarted) return 0;
        stop.exitStarted = true; return stop.direction;
    }
    public synchronized void status(String symbol, String text) { Stop stop = stops.get(symbol); if (stop != null) stop.status = text; }
    public synchronized JsonObject view(String symbol) {
        JsonObject value = new JsonObject(); if (!active()) return value;
        Stop stop = stops.get(symbol); if (stop == null) return value;
        value.addProperty("price", stop.price); value.addProperty("status", stop.status);
        value.addProperty("triggered", stop.triggered); value.addProperty("monitoring", stop.net != 0 && !stop.triggered);
        value.addProperty("triggerPrice", stop.price + (stop.direction < 0 ? .01 : -.01)); return value;
    }
    public synchronized void close() { stops.clear(); }
}
