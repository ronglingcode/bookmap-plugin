package com.bookmap.plugin.rong;

import com.google.gson.JsonObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import static com.bookmap.plugin.rong.PatternObservationActivationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class BackgroundMarketCallbacksTest {
    @Test void callbacksHandoffWithoutWaitingAndHistoricalHandoffStaysOrdered() throws Exception {
        Object saved = field("sharedServer").get(null);
        SignalWebSocketServer server = new SignalWebSocketServer(0, 0);
        CountDownLatch busy = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicLong clock = new AtomicLong();
        AtomicReference<Thread> processing = new AtomicReference<>();
        RongPlugin plugin = plugin("TEST");
        try (MarketDataDispatcher dispatcher = new MarketDataDispatcher("TEST", 32, clock::get, message -> fail(message))) {
            field("sharedServer").set(null, server);
            OrderBookState book = new OrderBookState() {
                @Override public synchronized void update(boolean bid, int price, int size) {
                    processing.set(Thread.currentThread()); super.update(bid, price, size);
                }
            };
            field("orderBook").set(plugin, book);
            field("instrumentInfo").set(plugin, new InstrumentInfo("TEST", "NASDAQ", "STOCKS", .01, 1, "TEST", true));
            field("initialized").setBoolean(plugin, true);
            field("marketDataDispatcher").set(plugin, dispatcher);
            server.registerSymbol("TEST", book, .01);
            server.registerMarketDataHealth("TEST", dispatcher::healthy);
            server.beginSessionHistory("TEST");
            dispatcher.submit(() -> {
                busy.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(busy.await(3, TimeUnit.SECONDS));
            long open = SessionOrderSizeTrackerTest.OPEN;
            plugin.onTimestamp(open);
            plugin.onDepth(false, 10000, 10000);
            plugin.onTrade(10000, 6000, new TradeInfo(false, true));
            plugin.onSnapshotEnd();
            plugin.onRealtimeStart();
            assertEquals(0, book.getAskLevelCount());
            clock.set(250_000_001);
            JsonObject delayed = server.entrySizeCap("TEST");
            assertFalse(delayed.get("available").getAsBoolean());
            assertTrue(delayed.get("processingDelayed").getAsBoolean());
            assertFalse(server.entryWallSnapshot("TEST").has("largeAsks"));
            dispatcher.submit(done::countDown);
            release.countDown(); assertTrue(done.await(3, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), processing.get());
            assertEquals(open, field("lastTimestampNs").getLong(plugin));
            JsonObject cap = server.entrySizeCap("TEST");
            assertTrue(cap.get("available").getAsBoolean());
            assertFalse(cap.get("historyLoading").getAsBoolean());
            assertEquals(6000, cap.get("largestOrderSize").getAsLong());
            assertNull(field("patternEvents").get(plugin));
            assertNull(field("bidBreakdownMonitor").get(plugin));
            assertNull(field("falseBreakoutMonitor").get(plugin));
            assertNull(field("reclaimSecondBidMonitor").get(plugin));
        } finally {
            release.countDown(); field("initialized").setBoolean(plugin, false);
            field("sharedServer").set(null, saved); server.shutdown();
        }
    }
}
