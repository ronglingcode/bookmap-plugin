package com.bookmap.plugin.rong.miniviteapp.core.controllers;

import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.google.gson.JsonObject;
import java.util.function.LongSupplier;

/** Local, monotonic, single-use permission for one opening action. Never persisted. */
public final class SelectedStop {
    public final class Claim {
        public final double price;
        private final long time;
        private Claim(double price, long time) { this.price = price; this.time = time; }
        public void requireFresh() {
            long age = clock.getAsLong() - time;
            Models.require(age >= 0 && age < LIFETIME_NS, "Selected stop expired before submission; hover + Z again");
        }
    }
    public static final long LIFETIME_NS = 30_000_000_000L;
    private final LongSupplier clock;
    private String symbol = "";
    private double price;
    private long selectedAt;

    public SelectedStop() { this(System::nanoTime); }
    public SelectedStop(LongSupplier clock) { this.clock = clock; }
    public synchronized void select(String symbol, double price) {
        Models.require(symbol != null && !symbol.isBlank() && Models.positive(price), "Hover stop price and press Z");
        this.symbol = symbol; this.price = Math.round(price * 100) / 100.0;
        Models.require(this.price > 0, "Invalid stop price");
        selectedAt = clock.getAsLong();
    }
    public synchronized void clear() { symbol = ""; price = 0; }
    private boolean fresh() { long age = clock.getAsLong() - selectedAt; return price > 0 && age >= 0 && age < LIFETIME_NS; }
    public synchronized Claim consume(String symbol) {
        boolean valid = fresh() && this.symbol.equals(symbol);
        double result = price;
        clear();
        Models.require(valid, "Select stop: hover + Z (valid for 30 seconds, one entry)");
        return new Claim(result, selectedAt);
    }
    public synchronized JsonObject view() {
        if (!fresh()) clear();
        JsonObject result = new JsonObject();
        result.addProperty("symbol", symbol); result.addProperty("price", price);
        return result;
    }
}
