package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.runtime.MarketStreams;
import com.bookmap.plugin.rong.miniviteapp.ports.SocketPort;
import com.bookmap.plugin.rong.miniviteapp.models.Trade;
import com.google.gson.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketStreamsTest {
    static class Socket implements SocketPort.Connection {
        SocketPort.Handlers handlers; JsonArray sent = new JsonArray(); boolean closed;
        public void send(String data) { sent.add(JsonParser.parseString(data)); }
        public void close() { closed = true; }
        void receive(String data) { handlers.message(this, data); }
    }
    static class Task { long delay; Runnable run; boolean canceled; }
    @Test void reconnectRefreshesCredentialsAndCloseStopsBothConsumers() {
        List<Socket> sockets = new ArrayList<>(); List<Task> tasks = new ArrayList<>(); List<String> events = new ArrayList<>(); AtomicInteger tokens = new AtomicInteger();
        MarketStreams streams = new MarketStreams((url, handlers) -> { Socket socket = new Socket(); socket.handlers = handlers; sockets.add(socket); return socket; },
                (delay, run) -> { Task task = new Task(); task.delay = delay; task.run = run; tasks.add(task); return () -> task.canceled = true; }, Runnable::run,
                List.of("AAPL", "TSLA"), () -> "fake-key", () -> new MarketStreams.Credentials(JsonParser.parseString("{\"streamerSocketUrl\":\"wss://fake.invalid\",\"schwabClientCustomerId\":\"customer\",\"schwabClientCorrelId\":\"correl\",\"schwabClientChannel\":\"channel\",\"schwabClientFunctionId\":\"function\"}").getAsJsonObject(), "token-" + tokens.incrementAndGet()),
                new MarketStreams.Events() {
                    public void trade(Trade trade) { events.add("trade"); }
                    public void quote(JsonObject quote) { events.add("quote:" + quote.get("bidSize")); }
                    public void activity(JsonArray contents) { events.add("activity"); }
                    public void ready(String source) { events.add("ready:" + source); }
                    public void status(String source, String status) { events.add(source + ":" + status); }
                });
        streams.start(); assertEquals(2, sockets.size()); Socket massive = sockets.get(0), schwab = sockets.get(1);
        massive.handlers.opened(massive); schwab.handlers.opened(schwab);
        assertEquals("token-1", schwab.sent.get(0).getAsJsonObject().getAsJsonObject("parameters").get("Authorization").getAsString());
        massive.receive("[{\"ev\":\"status\",\"status\":\"auth_success\"}]"); assertEquals("T.AAPL,T.TSLA", massive.sent.get(1).getAsJsonObject().get("params").getAsString());
        schwab.receive("{\"response\":[{\"service\":\"ADMIN\",\"command\":\"LOGIN\",\"content\":{\"code\":3}}]}");
        assertTrue(schwab.closed); assertEquals(1, schwab.sent.size()); assertEquals(1000, tasks.get(0).delay);
        tasks.get(0).run.run(); Socket replacement = sockets.get(2); replacement.handlers.opened(replacement);
        assertEquals("token-2", replacement.sent.get(0).getAsJsonObject().getAsJsonObject("parameters").get("Authorization").getAsString());
        replacement.receive("{\"response\":[{\"service\":\"ADMIN\",\"command\":\"LOGIN\",\"content\":{\"code\":0}}]}"); assertEquals(3, replacement.sent.size());
        replacement.receive("{\"data\":[{\"service\":\"LEVELONE_EQUITIES\",\"content\":[{\"key\":\"AAPL\",\"4\":0}]},{\"service\":\"ACCT_ACTIVITY\",\"content\":[{\"2\":\"OrderAccepted\"}]}]}");
        assertTrue(events.contains("quote:0")); assertTrue(events.contains("activity"));
        massive.receive("[{\"ev\":\"T\",\"sym\":\"AAPL\",\"p\":10,\"s\":100,\"t\":1790861400000,\"q\":1},{\"ev\":\"T\",\"sym\":\"AAPL\",\"p\":99,\"s\":100,\"t\":1790861400000,\"q\":2,\"c\":[37]}]");
        assertEquals(1, events.stream().filter(event -> event.equals("trade")).count());
        replacement.handlers.closed(); assertEquals(1000, tasks.get(1).delay); streams.close();
        assertTrue(tasks.get(1).canceled); assertTrue(massive.closed); int count = events.size();
        replacement.receive("{bad"); tasks.get(1).run.run(); assertEquals(3, sockets.size()); assertEquals(count, events.size());
    }
}
