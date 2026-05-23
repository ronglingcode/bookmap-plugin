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

    private final Object schedulerLock = new Object();
    private ScheduledExecutorService scheduler;
    private final Path heartbeatLogFile;
    private final Path breakoutLogFile;
    private BufferedWriter heartbeatWriter;
    private BufferedWriter breakoutWriter;

    // Per-symbol state
    private final Map<String, OrderBookState> symbolToOrderBook = new ConcurrentHashMap<>();
    private final Map<String, Double> symbolToPips = new ConcurrentHashMap<>();
    private final Map<String, Double> symbolToLastPrice = new ConcurrentHashMap<>();
    private final Map<String, List<TradebookButtonGroup>> symbolToTradebooks = new ConcurrentHashMap<>();
    private final Map<String, Set<TradeButtonConfigListener>> symbolToTradeButtonListeners = new ConcurrentHashMap<>();

    // Broadcast config
    private final double orderbookPercentile;
    private final int orderbookIntervalMs;
    private final Set<WebSocket> orderbookSubscribers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private ScheduledFuture<?> orderbookBroadcastTask;
    private ScheduledFuture<?> heartbeatTask;
    private volatile boolean shuttingDown;

    public SignalWebSocketServer(int port, double orderbookPercentile, int orderbookIntervalMs) {
        super(new InetSocketAddress("127.0.0.1", port));
        setDaemon(true);
        setReuseAddr(true);
        this.orderbookPercentile = orderbookPercentile;
        this.orderbookIntervalMs = orderbookIntervalMs;
        Path signalsDir = Paths.get(System.getProperty("user.home"), "Bookmap", "bookmap-signals");
        this.heartbeatLogFile = signalsDir.resolve("heartbeat.jsonl");
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
        symbolToLastPrice.remove(symbol);
        PluginLog.info("[Rong] Unregistered symbol: " + symbol);
    }

    public void setLastPrice(String symbol, double price) {
        symbolToLastPrice.put(symbol, price);
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
            Files.createDirectories(heartbeatLogFile.getParent());
            heartbeatWriter = Files.newBufferedWriter(heartbeatLogFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            breakoutWriter = Files.newBufferedWriter(breakoutLogFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            PluginLog.info("[Rong] Logging to " + heartbeatLogFile.getParent());
        } catch (IOException e) {
            PluginLog.error("[Rong] Failed to open log files: " + e.getMessage());
        }
        synchronized (schedulerLock) {
            if (shuttingDown || heartbeatTask != null) {
                return;
            }
            try {
                heartbeatTask = getOrCreateScheduler().scheduleAtFixedRate(this::sendHeartbeats, 5, 5, TimeUnit.SECONDS);
            } catch (RejectedExecutionException e) {
                PluginLog.error("[Rong] Failed to schedule heartbeats: " + e.getMessage());
            }
        }
    }

    public void broadcastSignal(String json) {
        writeToFile(breakoutWriter, json);
        broadcast(json);
    }

    public void shutdown() {
        shuttingDown = true;
        synchronized (schedulerLock) {
            if (heartbeatTask != null) {
                heartbeatTask.cancel(true);
                heartbeatTask = null;
            }
            if (orderbookBroadcastTask != null) {
                orderbookBroadcastTask.cancel(true);
                orderbookBroadcastTask = null;
            }
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
        }
        closeWriter(heartbeatWriter);
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
                    conn.send(orderbookJson);
                }
            }
        }
    }

    /** Send heartbeats for ALL registered symbols. */
    private void sendHeartbeats() {
        for (Map.Entry<String, Double> entry : symbolToLastPrice.entrySet()) {
            String symbol = entry.getKey();
            double price = entry.getValue();
            String json = String.format(
                "{\"type\":\"heartbeat\",\"symbol\":\"%s\",\"price\":%.6f,\"timestamp\":%d}",
                symbol, price, System.currentTimeMillis());
            writeToFile(heartbeatWriter, json);
            if (!getConnections().isEmpty()) {
                broadcast(json);
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
