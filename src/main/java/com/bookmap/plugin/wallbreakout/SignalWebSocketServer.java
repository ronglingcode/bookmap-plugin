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
    private final Path heartbeatLogFile;
    private final Path breakoutLogFile;
    private BufferedWriter heartbeatWriter;
    private BufferedWriter breakoutWriter;

    public SignalWebSocketServer(int port) {
        super(new InetSocketAddress("127.0.0.1", port));
        setDaemon(true);
        setReuseAddr(true);
        Path signalsDir = Paths.get(System.getProperty("user.home"), "bookmap-signals");
        this.heartbeatLogFile = signalsDir.resolve("heartbeat.jsonl");
        this.breakoutLogFile = signalsDir.resolve("breakout.jsonl");
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
            Files.createDirectories(heartbeatLogFile.getParent());
            heartbeatWriter = Files.newBufferedWriter(heartbeatLogFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            breakoutWriter = Files.newBufferedWriter(breakoutLogFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("[WallBreakout] Logging to " + heartbeatLogFile.getParent());
        } catch (IOException e) {
            System.err.println("[WallBreakout] Failed to open log files: " + e.getMessage());
        }
        heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeat, 5, 5, TimeUnit.SECONDS);
    }

    public void setLastPrice(double price) {
        lastPrice.set(price);
    }

    public void broadcastSignal(String json) {
        writeToFile(breakoutWriter, json);
        broadcast(json);
    }

    public void shutdown() {
        heartbeatScheduler.shutdownNow();
        closeWriter(heartbeatWriter);
        closeWriter(breakoutWriter);
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
