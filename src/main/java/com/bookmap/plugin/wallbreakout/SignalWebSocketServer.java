package com.bookmap.plugin.wallbreakout;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

public class SignalWebSocketServer extends WebSocketServer {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "ws-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final Path heartbeatLogFile;
    private final Path breakoutLogFile;
    private BufferedWriter heartbeatWriter;
    private BufferedWriter breakoutWriter;

    // Per-symbol state
    private final Map<String, OrderBookState> symbolToOrderBook = new ConcurrentHashMap<>();
    private final Map<String, Double> symbolToPips = new ConcurrentHashMap<>();
    private final Map<String, Double> symbolToLastPrice = new ConcurrentHashMap<>();

    // Broadcast config
    private final double orderbookPercentile;
    private final int orderbookIntervalMs;
    private final Set<WebSocket> orderbookSubscribers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private ScheduledFuture<?> orderbookBroadcastTask;

    public SignalWebSocketServer(int port, double orderbookPercentile, int orderbookIntervalMs) {
        super(new InetSocketAddress("127.0.0.1", port));
        setDaemon(true);
        setReuseAddr(true);
        this.orderbookPercentile = orderbookPercentile;
        this.orderbookIntervalMs = orderbookIntervalMs;
        Path signalsDir = Paths.get(System.getProperty("user.home"), "ProgramLogs", "bookmap-signals");
        this.heartbeatLogFile = signalsDir.resolve("heartbeat.jsonl");
        this.breakoutLogFile = signalsDir.resolve("breakout.jsonl");
    }

    /** Register a symbol's order book and pips multiplier. */
    public void registerSymbol(String symbol, OrderBookState orderBook, double pips) {
        symbolToOrderBook.put(symbol, orderBook);
        symbolToPips.put(symbol, pips);
        System.out.println("[WallBreakout] Registered symbol: " + symbol);
    }

    /** Unregister a symbol when its plugin instance stops. */
    public void unregisterSymbol(String symbol) {
        symbolToOrderBook.remove(symbol);
        symbolToPips.remove(symbol);
        symbolToLastPrice.remove(symbol);
        System.out.println("[WallBreakout] Unregistered symbol: " + symbol);
    }

    public void setLastPrice(String symbol, double price) {
        symbolToLastPrice.put(symbol, price);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("[WallBreakout] Client connected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        orderbookSubscribers.remove(conn);
        System.out.println("[WallBreakout] Client disconnected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        String trimmed = message.trim();
        if (trimmed.contains("\"subscribe\"") && trimmed.contains("\"orderbook\"")) {
            orderbookSubscribers.add(conn);
            ensureOrderbookBroadcast();
            conn.send("{\"type\":\"subscribed\",\"channel\":\"orderbook\",\"intervalMs\":" + orderbookIntervalMs + ",\"percentile\":" + orderbookPercentile + "}");
            System.out.println("[WallBreakout] Client subscribed to orderbook (interval=" + orderbookIntervalMs + "ms, percentile=" + orderbookPercentile + ")");
        } else if (trimmed.contains("\"unsubscribe\"") && trimmed.contains("\"orderbook\"")) {
            orderbookSubscribers.remove(conn);
            conn.send("{\"type\":\"unsubscribed\",\"channel\":\"orderbook\"}");
            System.out.println("[WallBreakout] Client unsubscribed from orderbook");
        }
    }

    private synchronized void ensureOrderbookBroadcast() {
        if (orderbookBroadcastTask != null) {
            return;
        }
        orderbookBroadcastTask = scheduler.scheduleAtFixedRate(
            () -> sendOrderbookSnapshots(),
            0, orderbookIntervalMs, TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[WallBreakout] WebSocket error: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        System.out.println("[WallBreakout] WebSocket server started on port " + getPort());
        try {
            Files.createDirectories(heartbeatLogFile.getParent());
            heartbeatWriter = Files.newBufferedWriter(heartbeatLogFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            breakoutWriter = Files.newBufferedWriter(breakoutLogFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("[WallBreakout] Logging to " + heartbeatLogFile.getParent());
        } catch (IOException e) {
            System.err.println("[WallBreakout] Failed to open log files: " + e.getMessage());
        }
        scheduler.scheduleAtFixedRate(this::sendHeartbeats, 5, 5, TimeUnit.SECONDS);
    }

    public void broadcastSignal(String json) {
        writeToFile(breakoutWriter, json);
        broadcast(json);
    }

    public void shutdown() {
        scheduler.shutdownNow();
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
                System.err.println("[WallBreakout] Failed to write to log: " + e.getMessage());
            }
        }
    }

    private void closeWriter(BufferedWriter writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                System.err.println("[WallBreakout] Failed to close log file: " + e.getMessage());
            }
        }
    }
}
