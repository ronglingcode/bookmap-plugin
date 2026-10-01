package com.bookmap.plugin.rong.miniviteapp.models;

import com.google.gson.JsonObject;

/** Domain time is epoch milliseconds. No chart timestamps or Bookmap coordinates. */
public final class Candle {
    public final String symbol;
    public final long datetime;
    public final double open, high, low, close, volume, vwap;
    public Candle(String symbol, long datetime, double open, double high, double low, double close, double volume, double vwap) {
        this.symbol = symbol; this.datetime = datetime; this.open = open; this.high = high;
        this.low = low; this.close = close; this.volume = volume; this.vwap = vwap;
    }
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("symbol", symbol); json.addProperty("datetime", datetime); json.addProperty("open", open);
        json.addProperty("high", high); json.addProperty("low", low); json.addProperty("close", close);
        json.addProperty("volume", volume); json.addProperty("vwap", vwap); return json;
    }
}
