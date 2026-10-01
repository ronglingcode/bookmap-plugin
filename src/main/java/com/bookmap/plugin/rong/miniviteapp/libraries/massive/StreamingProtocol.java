package com.bookmap.plugin.rong.miniviteapp.libraries.massive;

import com.bookmap.plugin.rong.miniviteapp.models.Trade;
import com.google.gson.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class StreamingProtocol {
    public static final String STREAM_URL = "wss://socket.massive.com/stocks";
    private StreamingProtocol() { }
    public static JsonObject loginRequest(String key) { JsonObject result = new JsonObject(); result.addProperty("action", "auth"); result.addProperty("params", key); return result; }
    public static JsonObject subscribeRequest(List<String> symbols) { JsonObject result = new JsonObject(); result.addProperty("action", "subscribe"); result.addProperty("params", symbols.stream().map(symbol -> "T." + symbol).collect(Collectors.joining(","))); return result; }
    public static final class Parsed {
        public String login;
        public final List<Trade> trades = new ArrayList<>();
        public JsonObject toJson() {
            JsonObject result = new JsonObject(); if (login != null) result.addProperty("login", login); JsonArray values = new JsonArray(); trades.forEach(trade -> values.add(trade.toJson())); result.add("trades", values); return result;
        }
    }
    public static Parsed parseStreamMessage(JsonArray values) {
        Parsed result = new Parsed();
        for (JsonElement element : values) {
            JsonObject value = element.getAsJsonObject();
            if (value.has("ev") && value.get("ev").getAsString().equals("status") && value.has("status")) {
                String status = value.get("status").getAsString(); if (status.equals("auth_success")) result.login = "success";
                if (status.equals("auth_failed") || status.equals("not_authorized")) result.login = "failed";
            }
            Trade trade = Mapper.mapWebSocketTrade(value); if (trade != null && !Mapper.shouldFilterTrade(trade)) result.trades.add(trade);
        }
        return result;
    }
}
