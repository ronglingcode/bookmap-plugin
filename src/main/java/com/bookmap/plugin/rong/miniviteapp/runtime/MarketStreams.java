package com.bookmap.plugin.rong.miniviteapp.runtime;

import com.bookmap.plugin.rong.miniviteapp.models.Trade;
import com.bookmap.plugin.rong.miniviteapp.ports.SocketPort;
import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.StreamingProtocol;
import com.google.gson.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Same two vendor streams as TS MarketStreams; Bookmap integration uses Events only. */
public final class MarketStreams implements AutoCloseable {
    public interface Events {
        void trade(Trade trade); void quote(JsonObject quote); void activity(JsonArray contents);
        void ready(String source); void status(String source, String status);
    }
    public static final class Credentials {
        public final JsonObject info; public final String token;
        public Credentials(JsonObject info, String token) { this.info = info; this.token = token; }
    }
    private final ManagedSocket massive, schwab;
    public MarketStreams(SocketPort sockets, SocketPort.Scheduler scheduler, Executor executor, List<String> symbols, Supplier<String> key,
                         Callable<Credentials> credentials, Events events) {
        massive = new ManagedSocket(sockets, scheduler, executor, () -> new ManagedSocket.Setup() {
            public String url() { return com.bookmap.plugin.rong.miniviteapp.libraries.massive.StreamingProtocol.STREAM_URL; }
            public void opened(SocketPort.Connection socket) { socket.send(com.bookmap.plugin.rong.miniviteapp.libraries.massive.StreamingProtocol.loginRequest(key.get()).toString()); }
            public void message(SocketPort.Connection socket, String data) {
                var parsed = com.bookmap.plugin.rong.miniviteapp.libraries.massive.StreamingProtocol.parseStreamMessage(JsonParser.parseString(data).getAsJsonArray());
                if ("failed".equals(parsed.login)) { events.status("massive", "authentication failed"); massive.reconnect(); return; }
                if ("success".equals(parsed.login)) { massive.ready(); events.ready("massive"); socket.send(com.bookmap.plugin.rong.miniviteapp.libraries.massive.StreamingProtocol.subscribeRequest(symbols).toString()); }
                parsed.trades.forEach(events::trade);
            }
        }, status -> events.status("massive", status));
        schwab = new ManagedSocket(sockets, scheduler, executor, () -> {
            Credentials value = credentials.call();
            return new ManagedSocket.Setup() {
                public String url() { return value.info.get("streamerSocketUrl").getAsString(); }
                public void opened(SocketPort.Connection socket) { socket.send(StreamingProtocol.loginRequest(value.info, value.token).toString()); }
                public void message(SocketPort.Connection socket, String data) {
                    JsonObject parsed = StreamingProtocol.parseStreamMessage(JsonParser.parseString(data).getAsJsonObject()); String login = parsed.has("login") ? parsed.get("login").getAsString() : "";
                    if (login.equals("failed")) { events.status("schwab", "authentication failed"); schwab.reconnect(); return; }
                    if (login.equals("success")) {
                        schwab.ready(); events.ready("schwab"); socket.send(StreamingProtocol.quoteSubscribeRequest(value.info, symbols).toString()); socket.send(StreamingProtocol.activitySubscribeRequest(value.info).toString());
                    }
                    parsed.getAsJsonArray("quotes").forEach(quote -> events.quote(quote.getAsJsonObject()));
                    if (parsed.getAsJsonArray("activities").size() > 0) events.activity(parsed.getAsJsonArray("activities"));
                }
            };
        }, status -> events.status("schwab", status));
    }
    public void start() { massive.start(); schwab.start(); }
    @Override public void close() { massive.close(); schwab.close(); }
}
