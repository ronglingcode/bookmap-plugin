package com.bookmap.plugin.rong.miniviteapp.core.controllers;

import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.google.gson.JsonObject;
import java.util.function.LongSupplier;

/** Local, monotonic, single-use permission for one opening action. Never persisted. */
public final class SelectedStop {
    public final class Claim {
        public final double price;
        private final long time;
        private boolean committed;
        private Claim(double price, long time) { this.price = price; this.time = time; }
        public void requireFresh() {
            long age = clock.getAsLong() - time;
            Models.require(age >= 0 && age < LIFETIME_NS, "Selected stop expired before submission; hover + Z again");
        }
        /** Commit only at the first opening dispatch, never while validating a plan. */
        public void commit() {
            synchronized (SelectedStop.this) {
                requireFresh();
                if (committed) return;
                Models.require(reserved == this, "Selected stop changed before submission; hover + Z again");
                committed = true;
                clear();
            }
        }
        public void release() {
            synchronized (SelectedStop.this) { if (reserved == this) reserved = null; }
        }
    }
    public static final long LIFETIME_NS = 30_000_000_000L;
    private final LongSupplier clock;
    private String symbol = "";
    private double price;
    private long selectedAt;
    private Claim reserved;
    private long generation;
    public synchronized long generation() { return generation; }

    public SelectedStop() { this(System::nanoTime); }
    public SelectedStop(LongSupplier clock) { this.clock = clock; }
    public synchronized void select(String symbol, double price) {
        Models.require(symbol != null && !symbol.isBlank() && Models.positive(price), "Hover stop price and press Z");
        this.symbol = symbol; this.price = Math.round(price * 100) / 100.0;
        reserved = null;
        generation++;
        Models.require(this.price > 0, "Invalid stop price");
        selectedAt = clock.getAsLong();
    }
    public synchronized void clear() { symbol = ""; price = 0; reserved = null; generation++; }
    private boolean fresh() { long age = clock.getAsLong() - selectedAt; return price > 0 && age >= 0 && age < LIFETIME_NS; }
    public synchronized Claim consume(String symbol) {
        boolean valid = fresh() && this.symbol.equals(symbol);
        double result = price;
        clear();
        Models.require(valid, "Select stop: hover + Z (valid for 30 seconds, one entry)");
        return new Claim(result, selectedAt);
    }
    public synchronized Claim reserve(String symbol) {
        Models.require(fresh() && this.symbol.equals(symbol), "Select stop: hover + Z (valid for 30 seconds, one entry)");
        Models.require(reserved == null, "Selected stop already reserved by an entry");
        return reserved = new Claim(price, selectedAt);
    }
    public synchronized JsonObject view() {
        long remaining = LIFETIME_NS - (clock.getAsLong() - selectedAt);
        if (remaining <= 0 || remaining > LIFETIME_NS) clear();
        JsonObject result = new JsonObject();
        result.addProperty("symbol", symbol); result.addProperty("price", price);
        result.addProperty("remainingSeconds", price > 0 ? (remaining + 999_999_999L) / 1_000_000_000L : 0);
        return result;
    }
}
