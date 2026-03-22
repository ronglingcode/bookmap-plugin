package com.bookmap.plugin.common;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Fetches indicator data (Camarilla Pivots + Premarket High/Low) from the EdgeDesk API
 * and dispatches it to the appropriate trackers.
 *
 * <p>Makes a single HTTP GET to the intraday-indicators endpoint on a background thread.
 * The response contains pre-calculated cam pivots and premarket high/low, avoiding the
 * need for the Bookmap plugin to have its own API key or date logic.</p>
 */
public class IndicatorDataFetcher {

    private static final String API_BASE_URL = "https://edgedesk-production.up.railway.app";

    private IndicatorDataFetcher() {} // utility class

    /**
     * Fetch indicator data from the EdgeDesk API and dispatch to trackers.
     * Runs on a background thread to avoid blocking Bookmap's data thread.
     *
     * @param instrumentAlias instrument identifier (e.g. "AAPL", "ESZ4")
     * @param pips            price multiplier (tick-to-real-price conversion)
     * @param camPivotTracker tracker to receive cam pivot levels (may be null)
     * @param premarketTracker tracker to receive premarket high/low (may be null)
     */
    public static void fetch(String instrumentAlias, double pips,
                              CamPivotTracker camPivotTracker,
                              PremarketTracker premarketTracker) {
        // Extract ticker from instrument alias (Bookmap may append exchange suffix like "@ISLAND")
        String ticker = instrumentAlias.contains("@")
                ? instrumentAlias.substring(0, instrumentAlias.indexOf("@"))
                : instrumentAlias;

        Thread thread = new Thread(() -> {
            try {
                String urlStr = API_BASE_URL + "/api/intraday-indicators?test=true&ticker=" + ticker;
                PluginLog.info("[IndicatorDataFetcher] Fetching: " + urlStr);

                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);

                int status = conn.getResponseCode();
                if (status != 200) {
                    PluginLog.error("[IndicatorDataFetcher] HTTP " + status + " for " + ticker);
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }

                JsonObject json = JsonParser.parseString(sb.toString()).getAsJsonObject();

                // --- Cam Pivots ---
                if (camPivotTracker != null && json.has("camPivots") && !json.get("camPivots").isJsonNull()) {
                    JsonObject pivots = json.getAsJsonObject("camPivots");
                    Map<String, Double> pivotLevels = new HashMap<>();
                    String[] levels = {"R1", "R2", "R3", "R4", "R5", "R6",
                                       "S1", "S2", "S3", "S4", "S5", "S6"};
                    for (String level : levels) {
                        JsonElement el = pivots.get(level);
                        if (el != null && !el.isJsonNull()) {
                            pivotLevels.put(level, el.getAsDouble());
                        }
                    }
                    if (!pivotLevels.isEmpty()) {
                        camPivotTracker.drawPivots(instrumentAlias, pips, pivotLevels);
                    }
                }

                // --- Premarket High/Low ---
                if (premarketTracker != null && json.has("premarket") && !json.get("premarket").isJsonNull()) {
                    JsonObject pm = json.getAsJsonObject("premarket");
                    JsonElement highEl = pm.get("high");
                    JsonElement lowEl = pm.get("low");
                    if (highEl != null && !highEl.isJsonNull() && lowEl != null && !lowEl.isJsonNull()) {
                        double high = highEl.getAsDouble();
                        double low = lowEl.getAsDouble();
                        premarketTracker.seedFromApi(instrumentAlias, pips, high, low);
                    }
                }

                PluginLog.info("[IndicatorDataFetcher] Successfully loaded indicators for " + ticker);

            } catch (Exception e) {
                PluginLog.error("[IndicatorDataFetcher] Failed for " + ticker + ": " + e.getMessage());
            }
        }, "IndicatorDataFetcher-" + ticker);

        thread.setDaemon(true);
        thread.start();
    }
}
