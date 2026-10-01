package com.bookmap.plugin.rong.miniviteapp.libraries.firestore;

import com.bookmap.plugin.rong.miniviteapp.core.marketdata.MarketClock;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class LogRepository {
    private final Api api;
    private final Supplier<String> profile;
    private final LongSupplier now;
    public LogRepository(Api api, Supplier<String> profile) { this(api, profile, System::currentTimeMillis); }
    public LogRepository(Api api, Supplier<String> profile, LongSupplier now) { this.api = api; this.profile = profile; this.now = now; }
    private JsonObject metadata(int days) {
        long time = now.getAsLong(); String[] date = MarketClock.marketTime(time).date.split("-");
        JsonObject result = new JsonObject(); result.add("timestamp", DocumentCodec.timestamp(time));
        result.addProperty("dateStr", Integer.parseInt(date[0]) + "-" + Integer.parseInt(date[1]) + "-" + Integer.parseInt(date[2]));
        result.add("expiredAt", DocumentCodec.timestamp(time + days * 86400000L)); return result;
    }
    public void log(String type, JsonElement msg, JsonObject tags) throws Exception {
        JsonObject result = new JsonObject(); result.addProperty("msg", msg.toString()); result.addProperty("type", type);
        merge(result, metadata(7)); merge(result, tags); api.addDocument(profile.get() + "-Logs", result);
    }
    public void logOrder(JsonElement order, JsonObject tags) throws Exception {
        JsonObject result = metadata(7); result.addProperty("logOrder", order.toString()); merge(result, tags);
        api.addDocument(profile.get() + "-Orders", result);
    }
    public void logBreakoutTradeState(String symbol, JsonObject state) throws Exception {
        JsonObject result = new JsonObject(); result.addProperty("symbol", symbol); merge(result, metadata(3)); merge(result, state);
        api.addDocument("BreakoutTradeState", result);
    }
    private static void merge(JsonObject target, JsonObject source) {
        if (source != null) source.entrySet().forEach(entry -> target.add(entry.getKey(), entry.getValue().deepCopy()));
    }
}
