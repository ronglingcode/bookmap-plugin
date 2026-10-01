package com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab;

import com.google.gson.*;
import java.util.List;

public final class StreamingProtocol {
    private StreamingProtocol() { }
    private static String string(JsonObject value, String key) { return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : ""; }
    private static JsonArray array(JsonObject value, String key) { return value.has(key) && value.get(key).isJsonArray() ? value.getAsJsonArray(key) : new JsonArray(); }
    private static JsonObject request(JsonObject info, String service, String id, String command, JsonObject parameters) {
        JsonObject result = new JsonObject(); result.addProperty("service", service); result.addProperty("requestid", id); result.addProperty("command", command);
        result.addProperty("SchwabClientCustomerId", string(info, "schwabClientCustomerId")); result.addProperty("SchwabClientCorrelId", string(info, "schwabClientCorrelId")); result.add("parameters", parameters); return result;
    }
    public static JsonObject loginRequest(JsonObject info, String token) {
        JsonObject parameters = new JsonObject(); parameters.addProperty("Authorization", token); parameters.addProperty("SchwabClientChannel", string(info, "schwabClientChannel")); parameters.addProperty("SchwabClientFunctionId", string(info, "schwabClientFunctionId"));
        return request(info, "ADMIN", "1", "LOGIN", parameters);
    }
    public static JsonObject quoteSubscribeRequest(JsonObject info, List<String> symbols) {
        JsonObject parameters = new JsonObject(); parameters.addProperty("keys", String.join(",", symbols)); parameters.addProperty("fields", "0,1,2,4,5"); return request(info, "LEVELONE_EQUITIES", "2", "SUBS", parameters);
    }
    public static JsonObject activitySubscribeRequest(JsonObject info) {
        JsonObject parameters = new JsonObject(); parameters.addProperty("keys", "Account Activity"); parameters.addProperty("fields", "0,1,2,3"); return request(info, "ACCT_ACTIVITY", "3", "SUBS", parameters);
    }
    public static JsonObject mapQuote(JsonObject value) {
        JsonObject quote = new JsonObject(); quote.addProperty("symbol", string(value, "key"));
        String[][] fields = {{"1", "bidPrice"}, {"2", "askPrice"}, {"4", "bidSize"}, {"5", "askSize"}};
        for (String[] field : fields) if (value.has(field[0]) && value.get(field[0]).isJsonPrimitive() && value.get(field[0]).getAsJsonPrimitive().isNumber() && Double.isFinite(value.get(field[0]).getAsDouble())) quote.add(field[1], value.get(field[0]));
        return quote;
    }
    public static JsonObject parseStreamMessage(JsonObject value) {
        JsonObject result = new JsonObject(); JsonArray quotes = new JsonArray(), activities = new JsonArray();
        for (JsonElement element : array(value, "response")) {
            JsonObject response = element.getAsJsonObject();
            if (string(response, "service").equals("ADMIN") && string(response, "command").equals("LOGIN")) {
                JsonElement code = response.has("content") && response.get("content").isJsonObject() ? response.getAsJsonObject("content").get("code") : null;
                result.addProperty("login", code != null && code.isJsonPrimitive() && code.getAsJsonPrimitive().isNumber() && code.getAsDouble() == 0 ? "success" : "failed");
            }
        }
        for (JsonElement element : array(value, "data")) {
            JsonObject data = element.getAsJsonObject();
            if (string(data, "service").equals("LEVELONE_EQUITIES")) for (JsonElement item : array(data, "content")) {
                JsonObject content = item.getAsJsonObject(); if (content.has("key") && content.get("key").isJsonPrimitive() && content.get("key").getAsJsonPrimitive().isString()) quotes.add(mapQuote(content));
            }
            if (string(data, "service").equals("ACCT_ACTIVITY")) activities.addAll(array(data, "content"));
        }
        result.add("quotes", quotes); result.add("activities", activities); return result;
    }
}
