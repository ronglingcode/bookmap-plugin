package com.bookmap.plugin.rong;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import com.bookmap.plugin.rong.tradebuttons.TradebookButtonGroup;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class SignalWebSocketServer extends WebSocketServer {

    @FunctionalInterface
    public interface TradeButtonConfigListener {
        void onTradeButtonsChanged(List<TradebookButtonGroup> tradebooks);
    }

    @FunctionalInterface
    public interface KeyLevelConfigListener {
        void onKeyLevelsChanged(String symbol, List<KeyLevelDefinition> levels);
    }

    @FunctionalInterface
    public interface ExitOrderPairsConfigListener {
        void onExitOrderPairsChanged(String symbol, List<ExitOrderPairDefinition> pairs);
    }

    private final Object schedulerLock = new Object();
    private ScheduledExecutorService scheduler;
    private final Path breakoutLogFile;
    private BufferedWriter breakoutWriter;

    // Per-symbol state
    private final Map<String, OrderBookState> symbolToOrderBook = new ConcurrentHashMap<>();
    private final Map<String, Double> symbolToPips = new ConcurrentHashMap<>();
    private final Map<String, List<TradebookButtonGroup>> symbolToTradebooks = new ConcurrentHashMap<>();
    private final Map<String, List<KeyLevelDefinition>> symbolToKeyLevels = new ConcurrentHashMap<>();
    private final Map<String, List<ExitOrderPairDefinition>> symbolToExitOrderPairs = new ConcurrentHashMap<>();
    private final Map<String, Set<TradeButtonConfigListener>> symbolToTradeButtonListeners = new ConcurrentHashMap<>();
    private final Set<KeyLevelConfigListener> keyLevelConfigListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<ExitOrderPairsConfigListener> exitOrderPairsConfigListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Broadcast config
    private final double orderbookPercentile;
    private final int orderbookIntervalMs;
    private final Set<WebSocket> orderbookSubscribers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private ScheduledFuture<?> orderbookBroadcastTask;
    private volatile boolean shuttingDown;

    public SignalWebSocketServer(int port, double orderbookPercentile, int orderbookIntervalMs) {
        super(new InetSocketAddress("127.0.0.1", port));
        setDaemon(true);
        setReuseAddr(true);
        this.orderbookPercentile = orderbookPercentile;
        this.orderbookIntervalMs = orderbookIntervalMs;
        Path signalsDir = Paths.get(System.getProperty("user.home"), "Bookmap", "bookmap-signals");
        this.breakoutLogFile = signalsDir.resolve("breakout.jsonl");
    }

    /** Register a symbol's order book and pips multiplier. */
    public void registerSymbol(String symbol, OrderBookState orderBook, double pips) {
        symbolToOrderBook.put(symbol, orderBook);
        symbolToPips.put(symbol, pips);
        PluginLog.info("[Rong] Registered symbol: " + symbol);
    }

    /** Unregister a symbol when its plugin instance stops. */
    public void unregisterSymbol(String symbol) {
        symbolToOrderBook.remove(symbol);
        symbolToPips.remove(symbol);
        PluginLog.info("[Rong] Unregistered symbol: " + symbol);
    }

    public void registerTradeButtonConfigListener(String symbol, TradeButtonConfigListener listener) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        symbolToTradeButtonListeners
                .computeIfAbsent(cleanSymbol, ignored -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(listener);

        List<TradebookButtonGroup> existingTradebooks = symbolToTradebooks.get(cleanSymbol);
        if (existingTradebooks != null) {
            listener.onTradeButtonsChanged(existingTradebooks);
        }
    }

    public void unregisterTradeButtonConfigListener(String symbol, TradeButtonConfigListener listener) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        Set<TradeButtonConfigListener> listeners = symbolToTradeButtonListeners.get(cleanSymbol);
        if (listeners == null) {
            return;
        }
        listeners.remove(listener);
        if (listeners.isEmpty()) {
            symbolToTradeButtonListeners.remove(cleanSymbol, listeners);
        }
    }

    public void registerKeyLevelConfigListener(KeyLevelConfigListener listener) {
        keyLevelConfigListeners.add(listener);
        for (Map.Entry<String, List<KeyLevelDefinition>> entry : symbolToKeyLevels.entrySet()) {
            listener.onKeyLevelsChanged(entry.getKey(), entry.getValue());
        }
    }

    public void unregisterKeyLevelConfigListener(KeyLevelConfigListener listener) {
        keyLevelConfigListeners.remove(listener);
    }

    public void registerExitOrderPairsConfigListener(ExitOrderPairsConfigListener listener) {
        exitOrderPairsConfigListeners.add(listener);
        for (Map.Entry<String, List<ExitOrderPairDefinition>> entry : symbolToExitOrderPairs.entrySet()) {
            listener.onExitOrderPairsChanged(entry.getKey(), entry.getValue());
        }
    }

    public void unregisterExitOrderPairsConfigListener(ExitOrderPairsConfigListener listener) {
        exitOrderPairsConfigListeners.remove(listener);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        PluginLog.info("[Rong] Client connected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        orderbookSubscribers.remove(conn);
        PluginLog.info("[Rong] Client disconnected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        String trimmed = message.trim();
        JsonObject json = parseJsonObject(trimmed);
        if (json != null) {
            String type = getString(json, "type");
            if ("trade_button_config".equals(type) || "trade_buttons_config".equals(type)) {
                handleTradeButtonConfig(json);
                return;
            }
            if ("key_levels_config".equals(type) || "key_level_config".equals(type)) {
                handleKeyLevelsConfig(json);
                return;
            }
            if ("exit_order_pairs_config".equals(type) || "exit_order_pair_config".equals(type)) {
                handleExitOrderPairsConfig(json);
                return;
            }
            if ("action_log".equals(type)) {
                handleActionLog(json);
                return;
            }
        }
        if (trimmed.contains("\"subscribe\"") && trimmed.contains("\"orderbook\"")) {
            orderbookSubscribers.add(conn);
            ensureOrderbookBroadcast();
            conn.send("{\"type\":\"subscribed\",\"channel\":\"orderbook\",\"intervalMs\":" + orderbookIntervalMs + ",\"percentile\":" + orderbookPercentile + "}");
            PluginLog.info("[Rong] Client subscribed to orderbook (interval=" + orderbookIntervalMs + "ms, percentile=" + orderbookPercentile + ")");
        } else if (trimmed.contains("\"unsubscribe\"") && trimmed.contains("\"orderbook\"")) {
            orderbookSubscribers.remove(conn);
            conn.send("{\"type\":\"unsubscribed\",\"channel\":\"orderbook\"}");
            PluginLog.info("[Rong] Client unsubscribed from orderbook");
        }
    }

    private JsonObject parseJsonObject(String message) {
        try {
            JsonElement element = JsonParser.parseString(message);
            if (element != null && element.isJsonObject()) {
                return element.getAsJsonObject();
            }
        } catch (RuntimeException e) {
            PluginLog.error("[TradeButton] Failed to parse WebSocket message: " + e.getMessage());
        }
        return null;
    }

    private void handleTradeButtonConfig(JsonObject json) {
        String symbol = SymbolUtils.cleanSymbol(getString(json, "symbol"));
        if (symbol.isEmpty()) {
            PluginLog.error("[TradeButton] Ignoring button config with missing symbol");
            return;
        }

        JsonArray tradebooksArray = null;
        JsonElement tradebooksElement = json.get("tradebooks");
        if (tradebooksElement != null && tradebooksElement.isJsonArray()) {
            tradebooksArray = tradebooksElement.getAsJsonArray();
        }
        if (tradebooksArray == null) {
            PluginLog.error("[TradeButton] Ignoring button config with missing tradebooks array for " + symbol);
            return;
        }

        List<TradebookButtonGroup> tradebooks = new ArrayList<>();
        for (JsonElement element : tradebooksArray) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject tradebookJson = element.getAsJsonObject();
            String id = getString(tradebookJson, "id");
            String tradebookId = getString(tradebookJson, "tradebookId");
            String tradebookName = getString(tradebookJson, "tradebookName");
            String label = getString(tradebookJson, "label");
            if (label.isEmpty()) {
                label = tradebookName;
            }
            if (label.isEmpty()) {
                label = tradebookId;
            }
            if (label.isEmpty()) {
                continue;
            }
            if (id.isEmpty()) {
                id = tradebookId.isEmpty() ? label : tradebookId;
            }
            List<String> entryMethods = getStringArray(tradebookJson, "entryMethods");
            if (entryMethods.isEmpty()) {
                continue;
            }
            tradebooks.add(new TradebookButtonGroup(
                    id,
                    label,
                    getString(tradebookJson, "side"),
                    tradebookId,
                    tradebookName,
                    entryMethods));
        }

        List<TradebookButtonGroup> immutableTradebooks = Collections.unmodifiableList(tradebooks);
        symbolToTradebooks.put(symbol, immutableTradebooks);
        notifyTradeButtonListeners(symbol, immutableTradebooks);
        PluginLog.info("[TradeButton] Updated " + tradebooks.size() + " tradebook button groups for " + symbol);
    }

    private void handleKeyLevelsConfig(JsonObject json) {
        String symbol = SymbolUtils.cleanSymbol(getString(json, "symbol"));
        if (symbol.isEmpty()) {
            PluginLog.error("[KeyLevel] Ignoring config with missing symbol");
            return;
        }

        JsonElement levelsElement = json.get("levels");
        if (levelsElement == null) {
            levelsElement = json.get("keyLevels");
        }
        if (levelsElement == null || !levelsElement.isJsonArray()) {
            PluginLog.error("[KeyLevel] Ignoring config with missing levels array for " + symbol);
            return;
        }

        List<KeyLevelDefinition> levels = new ArrayList<>();
        JsonArray levelsArray = levelsElement.getAsJsonArray();
        for (JsonElement element : levelsArray) {
            KeyLevelDefinition level = parseKeyLevel(symbol, element);
            if (level != null) {
                levels.add(level);
            }
        }

        List<KeyLevelDefinition> immutableLevels = Collections.unmodifiableList(levels);
        symbolToKeyLevels.put(symbol, immutableLevels);
        notifyKeyLevelConfigListeners(symbol, immutableLevels);
        PluginLog.info("[KeyLevel] Updated " + levels.size() + " websocket key levels for " + symbol);
    }

    private KeyLevelDefinition parseKeyLevel(String symbol, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        try {
            if (element.isJsonPrimitive()) {
                double price = element.getAsDouble();
                return price > 0 ? new KeyLevelDefinition(symbol, price, null) : null;
            }
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject levelJson = element.getAsJsonObject();
            double price = getDouble(levelJson, "price");
            if (price <= 0) {
                return null;
            }
            String label = getString(levelJson, "label");
            return new KeyLevelDefinition(symbol, price, label);
        } catch (RuntimeException e) {
            PluginLog.error("[KeyLevel] Ignoring malformed level for " + symbol + ": " + e.getMessage());
            return null;
        }
    }

    private void handleExitOrderPairsConfig(JsonObject json) {
        String symbol = SymbolUtils.cleanSymbol(getString(json, "symbol"));
        if (symbol.isEmpty()) {
            PluginLog.error("[ExitOrder] Ignoring config with missing symbol");
            return;
        }

        JsonElement pairsElement = json.get("pairs");
        if (pairsElement == null || !pairsElement.isJsonArray()) {
            PluginLog.error("[ExitOrder] Ignoring config with missing pairs array for " + symbol);
            return;
        }

        List<ExitOrderPairDefinition> pairs = new ArrayList<>();
        JsonArray pairsArray = pairsElement.getAsJsonArray();
        for (JsonElement element : pairsArray) {
            ExitOrderPairDefinition pair = parseExitOrderPair(symbol, element, pairs.size() + 1);
            if (pair != null) {
                pairs.add(pair);
            }
        }

        List<ExitOrderPairDefinition> immutablePairs = Collections.unmodifiableList(pairs);
        symbolToExitOrderPairs.put(symbol, immutablePairs);
        notifyExitOrderPairsConfigListeners(symbol, immutablePairs);
        PluginLog.info("[ExitOrder] Updated " + pairs.size() + " websocket exit pair(s) for " + symbol);
    }

    private void handleActionLog(JsonObject json) {
        String message = getString(json, "message").trim();
        String symbol = SymbolUtils.cleanSymbol(getString(json, "symbol"));
        String source = getString(json, "source").trim();
        if (!message.isEmpty()) {
            PluginLog.action(symbol, source, message);
        }
    }

    private ExitOrderPairDefinition parseExitOrderPair(String symbol, JsonElement element, int fallbackIndex) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        try {
            JsonObject pairJson = element.getAsJsonObject();
            int index = getInt(pairJson, "index");
            if (index <= 0) {
                index = fallbackIndex;
            }
            ExitOrderLegDefinition stop = parseExitOrderLeg(pairJson.get("STOP"));
            if (stop == null) {
                stop = parseExitOrderLeg(pairJson.get("stop"));
            }
            ExitOrderLegDefinition limit = parseExitOrderLeg(pairJson.get("LIMIT"));
            if (limit == null) {
                limit = parseExitOrderLeg(pairJson.get("limit"));
            }
            if (stop == null && limit == null) {
                return null;
            }
            return new ExitOrderPairDefinition(
                    symbol,
                    index,
                    getString(pairJson, "source"),
                    getString(pairJson, "parentOrderID"),
                    stop,
                    limit);
        } catch (RuntimeException e) {
            PluginLog.error("[ExitOrder] Ignoring malformed pair for " + symbol + ": " + e.getMessage());
            return null;
        }
    }

    private ExitOrderLegDefinition parseExitOrderLeg(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            return null;
        }
        JsonObject legJson = element.getAsJsonObject();
        double price = getDouble(legJson, "price");
        if (price <= 0 || !Double.isFinite(price)) {
            return null;
        }
        return new ExitOrderLegDefinition(
                getString(legJson, "orderID"),
                price,
                getInt(legJson, "quantity"),
                getBoolean(legJson, "isBuy"));
    }

    private void notifyKeyLevelConfigListeners(String symbol, List<KeyLevelDefinition> levels) {
        for (KeyLevelConfigListener listener : keyLevelConfigListeners) {
            try {
                listener.onKeyLevelsChanged(symbol, levels);
            } catch (RuntimeException e) {
                PluginLog.error("[KeyLevel] Failed to update listener for " + symbol + ": " + e.getMessage());
            }
        }
    }

    private void notifyExitOrderPairsConfigListeners(String symbol, List<ExitOrderPairDefinition> pairs) {
        for (ExitOrderPairsConfigListener listener : exitOrderPairsConfigListeners) {
            try {
                listener.onExitOrderPairsChanged(symbol, pairs);
            } catch (RuntimeException e) {
                PluginLog.error("[ExitOrder] Failed to update listener for " + symbol + ": " + e.getMessage());
            }
        }
    }

    private void notifyTradeButtonListeners(String symbol, List<TradebookButtonGroup> tradebooks) {
        Set<TradeButtonConfigListener> listeners = symbolToTradeButtonListeners.get(symbol);
        if (listeners == null) {
            return;
        }
        for (TradeButtonConfigListener listener : listeners) {
            try {
                listener.onTradeButtonsChanged(tradebooks);
            } catch (RuntimeException e) {
                PluginLog.error("[TradeButton] Failed to update button listener for " + symbol + ": " + e.getMessage());
            }
        }
    }

    private List<String> getStringArray(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || !element.isJsonArray()) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item == null || item.isJsonNull()) {
                continue;
            }
            try {
                String value = item.getAsString();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            } catch (RuntimeException e) {
                // Ignore malformed entries and keep the rest of the config usable.
            }
        }
        return values;
    }

    private String getString(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            return "";
        }
        try {
            return element.getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private double getDouble(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            return Double.NaN;
        }
        try {
            return element.getAsDouble();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private int getInt(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            return 0;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private boolean getBoolean(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            return false;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private synchronized void ensureOrderbookBroadcast() {
        if (shuttingDown || orderbookBroadcastTask != null) {
            return;
        }
        try {
            orderbookBroadcastTask = getOrCreateScheduler().scheduleAtFixedRate(
                () -> sendOrderbookSnapshots(),
                0, orderbookIntervalMs, TimeUnit.MILLISECONDS
            );
        } catch (RejectedExecutionException e) {
            PluginLog.error("[Rong] Failed to schedule orderbook broadcast: " + e.getMessage());
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        PluginLog.error("[Rong] WebSocket error: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        if (shuttingDown) {
            return;
        }
        PluginLog.info("[Rong] WebSocket server started on port " + getPort());
        try {
            Files.createDirectories(breakoutLogFile.getParent());
            breakoutWriter = Files.newBufferedWriter(breakoutLogFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            PluginLog.info("[Rong] Logging to " + breakoutLogFile.getParent());
        } catch (IOException e) {
            PluginLog.error("[Rong] Failed to open log files: " + e.getMessage());
        }
    }

    public void broadcastSignal(String json) {
        writeToFile(breakoutWriter, json);
        broadcast(json);
    }

    public void shutdown() {
        shuttingDown = true;
        synchronized (schedulerLock) {
            if (orderbookBroadcastTask != null) {
                orderbookBroadcastTask.cancel(true);
                orderbookBroadcastTask = null;
            }
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
        }
        closeWriter(breakoutWriter);
        try {
            stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Send orderbook snapshots for ALL registered symbols. */
    private void sendOrderbookSnapshots() {
        if (orderbookSubscribers.isEmpty()) return;
        for (Map.Entry<String, OrderBookState> entry : symbolToOrderBook.entrySet()) {
            String symbol = entry.getKey();
            OrderBookState orderBook = entry.getValue();
            Double pips = symbolToPips.get(symbol);
            if (pips == null) continue;

            String orderbookJson = orderBook.toJson(symbol, pips, orderbookPercentile);
            for (WebSocket conn : orderbookSubscribers) {
                if (conn.isOpen()) {
                    // Orderbook snapshot sending is disabled; keep snapshot computation available for now.
                    // conn.send(orderbookJson);
                }
            }
        }
    }

    private void writeToFile(BufferedWriter writer, String json) {
        if (writer != null) {
            try {
                writer.write(json);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                PluginLog.error("[Rong] Failed to write to log: " + e.getMessage());
            }
        }
    }

    private void closeWriter(BufferedWriter writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                PluginLog.error("[Rong] Failed to close log file: " + e.getMessage());
            }
        }
    }

    private ScheduledExecutorService getOrCreateScheduler() {
        synchronized (schedulerLock) {
            if (scheduler == null || scheduler.isShutdown()) {
                scheduler = Executors.newScheduledThreadPool(2, r -> {
                    Thread t = new Thread(r, "ws-scheduler");
                    t.setDaemon(true);
                    return t;
                });
            }
            return scheduler;
        }
    }
}
