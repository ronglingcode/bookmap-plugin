package com.bookmap.plugin.rong.miniviteapp.models;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

public final class Trade {
    public final String symbol, sequence, id;
    public final long timestamp;
    public final double price, size;
    public final Integer exchange;
    public final List<Integer> conditions;
    public Trade(String symbol, long timestamp, double price, double size, String sequence, String id, Integer exchange, List<Integer> conditions) {
        this.symbol = symbol; this.timestamp = timestamp; this.price = price; this.size = size;
        this.sequence = sequence; this.id = id; this.exchange = exchange; this.conditions = List.copyOf(conditions);
    }
    public JsonObject toJson() {
        JsonObject json = new JsonObject(); json.addProperty("symbol", symbol); json.addProperty("timestamp", timestamp);
        json.addProperty("price", price); json.addProperty("size", size);
        if (sequence != null) json.addProperty("sequence", sequence);
        if (id != null) json.addProperty("id", id); if (exchange != null) json.addProperty("exchange", exchange);
        JsonArray values = new JsonArray(); conditions.forEach(values::add); json.add("conditions", values); return json;
    }
}
