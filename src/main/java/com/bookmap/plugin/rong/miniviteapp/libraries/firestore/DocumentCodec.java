package com.bookmap.plugin.rong.miniviteapp.libraries.firestore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.time.Instant;

/** Explicit wire codec: compatible with Firebase Web SDK timestamp/map/state documents. */
public final class DocumentCodec {
    private DocumentCodec() {}
    public static JsonObject decodeFields(JsonObject fields) {
        JsonObject result = new JsonObject();
        if (fields != null) fields.entrySet().forEach(entry -> result.add(entry.getKey(), decodeValue(entry.getValue().getAsJsonObject())));
        return result;
    }
    public static JsonElement decodeValue(JsonObject value) {
        if (value.has("nullValue")) return JsonNull.INSTANCE;
        if (value.has("booleanValue")) return new JsonPrimitive(value.get("booleanValue").getAsBoolean());
        if (value.has("integerValue")) return new JsonPrimitive(value.get("integerValue").getAsDouble());
        if (value.has("doubleValue")) return new JsonPrimitive(value.get("doubleValue").getAsDouble());
        if (value.has("stringValue")) return new JsonPrimitive(value.get("stringValue").getAsString());
        if (value.has("timestampValue")) {
            Instant instant = Instant.parse(value.get("timestampValue").getAsString());
            JsonObject timestamp = new JsonObject(); timestamp.addProperty("seconds", instant.getEpochSecond()); timestamp.addProperty("nanoseconds", instant.getNano()); return timestamp;
        }
        if (value.has("arrayValue")) {
            JsonArray result = new JsonArray(); JsonObject array = value.getAsJsonObject("arrayValue");
            if (array.has("values")) for (JsonElement element : array.getAsJsonArray("values")) result.add(decodeValue(element.getAsJsonObject()));
            return result;
        }
        if (value.has("mapValue")) return decodeFields(value.getAsJsonObject("mapValue").getAsJsonObject("fields"));
        throw new IllegalArgumentException("Unsupported Firestore value type");
    }
    public static JsonObject encodeFields(JsonObject fields) {
        JsonObject result = new JsonObject(); fields.entrySet().forEach(entry -> result.add(entry.getKey(), encodeValue(entry.getValue()))); return result;
    }
    public static JsonObject encodeValue(JsonElement value) {
        JsonObject result = new JsonObject();
        if (value.isJsonNull()) result.add("nullValue", JsonNull.INSTANCE);
        else if (value.isJsonPrimitive()) {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isBoolean()) result.add("booleanValue", primitive);
            else if (primitive.isString()) result.add("stringValue", primitive);
            else {
                double number = primitive.getAsDouble();
                if (!Double.isFinite(number)) throw new IllegalArgumentException("Cannot persist a nonfinite Firestore number");
                if (number == Math.rint(number) && Math.abs(number) <= 9007199254740991d) result.addProperty("integerValue", Long.toString((long) number));
                else result.addProperty("doubleValue", number);
            }
        } else if (value.isJsonArray()) {
            JsonArray values = new JsonArray(); value.getAsJsonArray().forEach(element -> values.add(encodeValue(element)));
            JsonObject array = new JsonObject(); array.add("values", values); result.add("arrayValue", array);
        } else {
            JsonObject object = value.getAsJsonObject();
            if (object.size() == 2 && object.has("seconds") && object.has("nanoseconds")) {
                Instant instant = Instant.ofEpochSecond(object.get("seconds").getAsLong(), object.get("nanoseconds").getAsLong());
                // TS always emits nine fractional digits for SDK timestamps.
                String date = Instant.ofEpochSecond(instant.getEpochSecond()).toString().replace("Z", "");
                result.addProperty("timestampValue", date + "." + String.format(java.util.Locale.ROOT, "%09d", instant.getNano()) + "Z");
            } else {
                JsonObject map = new JsonObject(); map.add("fields", encodeFields(object)); result.add("mapValue", map);
            }
        }
        return result;
    }
    public static JsonObject timestamp(long epochMs) {
        Instant instant = Instant.ofEpochMilli(epochMs); JsonObject result = new JsonObject();
        result.addProperty("seconds", instant.getEpochSecond()); result.addProperty("nanoseconds", instant.getNano()); return result;
    }
}
