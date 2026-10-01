package com.bookmap.plugin.rong.miniviteapp.models;

import com.google.gson.*;

/** Explicit optional domain fields; no reflection on obfuscated DTO field names. */
public final class DomainJson {
    private DomainJson() { }
    public static JsonObject object(JsonObject json, String key) { return json != null && json.has(key) && json.get(key).isJsonObject() ? json.getAsJsonObject(key) : new JsonObject(); }
    public static JsonArray array(JsonObject json, String key) { return json != null && json.has(key) && json.get(key).isJsonArray() ? json.getAsJsonArray(key) : new JsonArray(); }
    public static String string(JsonObject json, String key) { return json != null && json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : ""; }
    public static double number(JsonObject json, String key) { try { double value = json != null && json.has(key) ? json.get(key).getAsDouble() : 0; return Double.isFinite(value) ? value : 0; } catch (RuntimeException e) { return 0; } }
    public static boolean bool(JsonObject json, String key) { return json != null && json.has(key) && json.get(key).isJsonPrimitive() && json.getAsJsonPrimitive(key).isBoolean() && json.get(key).getAsBoolean(); }
    public static JsonObject message(String type) { JsonObject result = new JsonObject(); result.addProperty("type", type); result.addProperty("priceUnit", "real"); result.addProperty("version", 3); return result; }
}
