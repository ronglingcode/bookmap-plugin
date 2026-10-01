package com.bookmap.plugin.rong.miniviteapp.adapters;

import com.bookmap.plugin.rong.miniviteapp.ports.SocketPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Native transport only; no Bookmap API, browser, proxy, or credentials in diagnostics. */
public final class JdkSockets implements SocketPort {
    private final HttpClient client;
    public JdkSockets() { client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build(); }
    @Override public Connection open(String url, Handlers handlers) {
        AtomicBoolean closed = new AtomicBoolean(); AtomicReference<WebSocket> current = new AtomicReference<>();
        Connection connection = new Connection() {
            public void send(String message) {
                WebSocket socket = current.get(); if (closed.get() || socket == null) return;
                socket.sendText(message, true).whenComplete((value, error) -> { if (error != null && !closed.get()) handlers.failed(); });
            }
            public void close() { closed.set(true); WebSocket socket = current.get(); if (socket != null) socket.abort(); }
        };
        client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(8)).buildAsync(URI.create(url), new WebSocket.Listener() {
            private final StringBuilder fragments = new StringBuilder();
            @Override public void onOpen(WebSocket socket) {
                current.set(socket); if (closed.get()) { socket.abort(); return; }
                handlers.opened(connection); socket.request(1);
            }
            @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                if (!closed.get()) {
                    fragments.append(data); if (last) { String text = fragments.toString(); fragments.setLength(0); handlers.message(connection, text); }
                    socket.request(1);
                }
                return null;
            }
            @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) { if (!closed.getAndSet(true)) handlers.closed(); return null; }
            @Override public void onError(WebSocket socket, Throwable error) { if (!closed.get()) handlers.failed(); }
        }).whenComplete((socket, error) -> { if (error != null && !closed.get()) handlers.failed(); });
        return connection;
    }
}
