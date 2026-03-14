package com.bookmap.plugin.wallbreakout;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

public class SignalWebSocketServer extends WebSocketServer {

    private static final int DEFAULT_ORDERBOOK_INTERVAL_MS = 1000;
    private static final int DEFAULT_ORDERBOOK_LEVELS = 20;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "ws-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<Double> lastPrice = new AtomicReference<>(Double.NaN);
    private final Path heartbeatLogFile;
    private final Path breakoutLogFile;
    private BufferedWriter heartbeatWriter;
    private BufferedWriter breakoutWriter;

    // Order book subscription state
    private final OrderBookState orderBook;
    private double pips = 1.0;
    private final Set<WebSocket> orderbookSubscribers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private ScheduledFuture<?> orderbookBroadcastTask;

    public SignalWebSocketServer(int port, OrderBookState orderBook) {
        super(new InetSocketAddress("127.0.0.1", port));
        setDaemon(true);
        setReuseAddr(true);
        this.orderBook = orderBook;
        Path signalsDir = Paths.get(System.getProperty("user.home"), "bookmap-signals");
        this.heartbeatLogFile = signalsDir.resolve("heartbeat.jsonl");
        this.breakoutLogFile = signalsDir.resolve("breakout.jsonl");
    }

    public void setPips(double pips) {
        this.pips = pips;
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
        // Handle subscription messages:
        //   {"type":"subscribe","channel":"orderbook"}                    — subscribe with defaults
        //   {"type":"subscribe","channel":"orderbook","intervalMs":500}   — custom interval
        //   {"type":"subscribe","channel":"orderbook","levels":10}        — custom depth
        //   {"type":"unsubscribe","channel":"orderbook"}                  — unsubscribe
        String trimmed = message.trim();
        if (trimmed.contains("\"subscribe\"") && trimmed.contains("\"orderbook\"")) {
            orderbookSubscribers.add(conn);
            int intervalMs = parseIntField(trimmed, "intervalMs", DEFAULT_ORDERBOOK_INTERVAL_MS);
            int levels = parseIntField(trimmed, "levels", DEFAULT_ORDERBOOK_LEVELS);
            ensureOrderbookBroadcast(intervalMs, levels);
            conn.send("{\"type\":\"subscribed\",\"channel\":\"orderbook\",\"intervalMs\":" + intervalMs + ",\"levels\":" + levels + "}");
            System.out.println("[WallBreakout] Client subscribed to orderbook (interval=" + intervalMs + "ms, levels=" + levels + ")");
        } else if (trimmed.contains("\"unsubscribe\"") && trimmed.contains("\"orderbook\"")) {
            orderbookSubscribers.remove(conn);
            conn.send("{\"type\":\"unsubscribed\",\"channel\":\"orderbook\"}");
            System.out.println("[WallBreakout] Client unsubscribed from orderbook");
        }
    }

    /**
     * Parse an integer field from a simple JSON string.
     * Falls back to defaultValue if field is not found.
     */
    private int parseIntField(String json, String field, int defaultValue) {
        String search = "\"" + field + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return defaultValue;
        idx += search.length();
        StringBuilder sb = new StringBuilder();
        while (idx < json.length() && (Character.isDigit(json.charAt(idx)) || json.charAt(idx) == ' ')) {
            if (Character.isDigit(json.charAt(idx))) {
                sb.append(json.charAt(idx));
            }
            idx++;
        }
        if (sb.length() == 0) return defaultValue;
        try {
            return Integer.parseInt(sb.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private synchronized void ensureOrderbookBroadcast(int intervalMs, int levels) {
        if (orderbookBroadcastTask != null) {
            orderbookBroadcastTask.cancel(false);
        }
        orderbookBroadcastTask = scheduler.scheduleAtFixedRate(
            () -> sendOrderbookSnapshot(levels),
            0, intervalMs, TimeUnit.MILLISECONDS
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
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 5, 5, TimeUnit.SECONDS);
    }

    public void setLastPrice(double price) {
        lastPrice.set(price);
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

    private void sendOrderbookSnapshot(int levels) {
        if (orderbookSubscribers.isEmpty()) return;
        String orderbookJson = orderBook.toJson(levels, pips);
        double price = lastPrice.get();
        String heartbeatJson = Double.isNaN(price) ? null : String.format(
            "{\"type\":\"heartbeat\",\"price\":%.6f,\"timestamp\":%d}",
            price, System.currentTimeMillis());
        for (WebSocket conn : orderbookSubscribers) {
            if (conn.isOpen()) {
                if (heartbeatJson != null) {
                    conn.send(heartbeatJson);
                }
                conn.send(orderbookJson);
            }
        }
    }

    private void sendHeartbeat() {
        double price = lastPrice.get();
        if (Double.isNaN(price)) {
            return;
        }
        String json = String.format(
            "{\"type\":\"heartbeat\",\"price\":%.6f,\"timestamp\":%d}",
            price, System.currentTimeMillis());
        writeToFile(heartbeatWriter, json);
        if (!getConnections().isEmpty()) {
            broadcast(json);
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
