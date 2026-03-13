package com.bookmap.plugin.wallbreakout;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

public class SignalWebSocketServer extends WebSocketServer {

    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-heartbeat");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<Double> lastPrice = new AtomicReference<>(Double.NaN);
    private final Path logFile;
    private BufferedWriter logWriter;

    public SignalWebSocketServer(int port) {
        super(new InetSocketAddress("127.0.0.1", port));
        setDaemon(true);
        setReuseAddr(true);
        this.logFile = Paths.get(System.getProperty("user.home"), "bookmap-signals", "heartbeat.jsonl");
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("[WallBreakout] Client connected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("[WallBreakout] Client disconnected: " + conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // No inbound messages expected
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[WallBreakout] WebSocket error: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        System.out.println("[WallBreakout] WebSocket server started on port " + getPort());
        try {
            Files.createDirectories(logFile.getParent());
            logWriter = Files.newBufferedWriter(logFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("[WallBreakout] Logging heartbeats to " + logFile);
        } catch (IOException e) {
            System.err.println("[WallBreakout] Failed to open log file: " + e.getMessage());
        }
        heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeat, 5, 5, TimeUnit.SECONDS);
    }

    public void setLastPrice(double price) {
        lastPrice.set(price);
    }

    public void broadcastSignal(String json) {
        broadcast(json);
    }

    public void shutdown() {
        heartbeatScheduler.shutdownNow();
        if (logWriter != null) {
            try {
                logWriter.close();
            } catch (IOException e) {
                System.err.println("[WallBreakout] Failed to close log file: " + e.getMessage());
            }
        }
        try {
            stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
        writeToLog(json);
        if (!getConnections().isEmpty()) {
            broadcast(json);
        }
    }

    private void writeToLog(String json) {
        if (logWriter != null) {
            try {
                logWriter.write(json);
                logWriter.newLine();
                logWriter.flush();
            } catch (IOException e) {
                System.err.println("[WallBreakout] Failed to write to log: " + e.getMessage());
            }
        }
    }
}
