package com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab;

import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import com.bookmap.plugin.rong.miniviteapp.ports.HttpPort;
import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Schwab extended-hours minute candles, bounded and deduplicated locally. */
public final class PriceHistoryApi {
    private final HttpPort http;
    public PriceHistoryApi(HttpPort http) { this.http = http; }
    public List<Candle> getMinuteBars(String symbol, long from, long toInclusive, String token) throws Exception {
        return getMinuteBars(symbol, from, toInclusive, token, false);
    }
    public List<Candle> getMinuteBars(String symbol, long from, long toInclusive, String token, boolean allowEmpty) throws Exception {
        String query = "symbol=" + URLEncoder.encode(symbol, StandardCharsets.UTF_8)
                + "&periodType=day&frequencyType=minute&frequency=1&startDate=" + from
                + "&endDate=" + toInclusive + "&needExtendedHoursData=true&needPreviousClose=false";
        HttpPort.Response response = http.request(URI.create("https://api.schwabapi.com/marketdata/v1/pricehistory?" + query),
                "GET", Map.of("Authorization", "Bearer " + token, "Accept", "application/json"), null);
        if (response.status != 200) throw new IOException("Schwab price history HTTP " + response.status);
        try {
            JsonObject json = JsonParser.parseString(response.body).getAsJsonObject();
            if (!json.has("candles") || !json.get("candles").isJsonArray()) throw new IllegalArgumentException();
            TreeMap<Long, Candle> candles = new TreeMap<>();
            for (JsonElement element : json.getAsJsonArray("candles")) {
                JsonObject value = element.getAsJsonObject();
                long timestamp = value.get("datetime").getAsLong();
                if (timestamp < from || timestamp > toInclusive) continue;
                double volume = number(value, "volume");
                if (volume < 0) throw new IllegalArgumentException();
                candles.put(timestamp, new Candle(symbol, timestamp, number(value, "open"), number(value, "high"),
                        number(value, "low"), number(value, "close"), volume, 0));
            }
            if (candles.isEmpty() && !allowEmpty) throw new IOException("Schwab returned no premarket candles");
            return new ArrayList<>(candles.values());
        } catch (RuntimeException malformed) { throw new IOException("Schwab price history returned invalid candles"); }
    }
    private static double number(JsonObject json, String name) {
        JsonPrimitive value = json.getAsJsonPrimitive(name);
        if (value == null || !value.isNumber()) throw new IllegalArgumentException();
        double number = value.getAsDouble();
        if (!Double.isFinite(number)) throw new IllegalArgumentException();
        return number;
    }
}
