package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.account.ExecutionExports.Format;
import com.bookmap.plugin.rong.miniviteapp.ports.*;
import com.bookmap.plugin.rong.miniviteapp.runtime.TradingRuntime;
import com.google.gson.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JournalExportRuntimeTest {
    private static final long BURST = Instant.parse("2026-10-26T13:30:00Z").toEpochMilli();
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

    private static final class Broker implements HttpPort {
        final List<JsonObject> orders = new ArrayList<>();
        final AtomicInteger orderReads = new AtomicInteger(), accountReads = new AtomicInteger();
        final AtomicLong now = new AtomicLong(BURST + 60000);
        int failAt;
        volatile boolean blockJournal;
        final CountDownLatch journalStarted = new CountDownLatch(1), releaseJournal = new CountDownLatch(1);
        Broker() {
            for (int id = 0; id < 501; id++) {
                JsonObject order = json("{\"orderStrategyType\":\"SINGLE\",\"orderType\":\"LIMIT\",\"status\":\"FILLED\",\"quantity\":1,\"filledQuantity\":1,\"price\":10,"
                        + "\"orderLegCollection\":[{\"legId\":1,\"instruction\":\"BUY\",\"positionEffect\":\"OPENING\",\"instrument\":{\"assetType\":\"EQUITY\"}}],"
                        + "\"orderActivityCollection\":[{\"activityType\":\"EXECUTION\",\"executionType\":\"FILL\",\"executionLegs\":[{\"legId\":1,\"quantity\":1,\"price\":10}]}]}");
                order.addProperty("orderId", id); order.addProperty("enteredTime", BURST + id);
                order.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject().getAsJsonObject("instrument").addProperty("symbol", id == 0 ? "EARLY" : "LATE");
                order.getAsJsonArray("orderActivityCollection").get(0).getAsJsonObject().getAsJsonArray("executionLegs").get(0).getAsJsonObject()
                        .addProperty("time", Instant.ofEpochMilli(BURST + id).toString());
                orders.add(order);
            }
        }
        public Response request(java.net.URI uri, String method, Map<String, String> headers, String body) throws Exception {
            assertEquals("GET", method, "Exports and account reads must not mutate broker state");
            if (uri.getPath().endsWith("/accounts")) {
                accountReads.incrementAndGet();
                return new Response(200, "[{\"securitiesAccount\":{\"positions\":[],\"currentBalances\":{\"liquidationValue\":25000}}}]");
            }
            assertTrue(uri.getPath().endsWith("/orders"));
            int call = orderReads.incrementAndGet();
            if (blockJournal && Thread.currentThread().getName().equals("journal-export-test")) {
                journalStarted.countDown(); assertTrue(releaseJournal.await(5, TimeUnit.SECONDS));
            }
            if (call == failAt) return new Response(503, "");
            Map<String, String> query = new HashMap<>();
            for (String field : uri.getQuery().split("&")) { String[] pair = field.split("=", 2); query.put(pair[0], pair[1]); }
            long from = Instant.parse(query.get("fromEnteredTime")).toEpochMilli(), to = Instant.parse(query.get("toEnteredTime")).toEpochMilli();
            List<JsonObject> matching = new ArrayList<>();
            for (JsonObject order : orders) {
                long entered = order.get("enteredTime").getAsLong();
                if (entered >= from && entered <= to) matching.add(order);
            }
            JsonArray page = new JsonArray(); matching.subList(Math.max(0, matching.size() - 500), matching.size()).forEach(page::add);
            return new Response(200, page.toString());
        }
        TradingRuntime runtime() {
            return new TradingRuntime(this, new CredentialPort() {
                public JsonObject loadSchwab() { return json("{\"access_token\":\"fake-token\",\"accountHashValue\":\"fake-account\",\"expires_at\":9999999999999}"); }
                public void saveSchwab(JsonObject value) { fail("No credential rotation expected"); }
            }, new JsonObject(), (url, handlers) -> { throw new AssertionError("No sockets needed"); },
                    (delay, task) -> () -> { }, Runnable::run, now::get, new TradingRuntime.Events() {
                        public void message(JsonObject value) { }
                        public void log(String symbol, String value) { }
                        public void notify(String symbol, String value) { }
                    });
        }
    }

    @Test void onlyExportsPaginateAndAllFormatsIncludeFillsMissingFromTheLivePage() {
        Broker broker = new Broker();
        try (TradingRuntime runtime = broker.runtime()) {
            runtime.refreshAccount();
            assertEquals(1, broker.orderReads.get(), "Live refresh must return immediately after one page");
            JsonObject before = runtime.view("LATE", "test");
            assertFalse(before.getAsJsonObject("account").getAsJsonObject("executions").has("EARLY"));
            for (Format format : Format.values()) {
                int readsBefore = broker.orderReads.get();
                String result = runtime.exportExecutions(format);
                assertTrue(broker.orderReads.get() - readsBefore > 1, "Export must retrieve complete history");
                assertTrue(result.contains("EARLY")); assertTrue(result.contains("LATE"));
                if (format == Format.CSV) assertEquals(501, result.lines().filter(line -> line.contains(",STOCK,BUY,")).count());
                assertEquals(before, runtime.view("LATE", "test"), "Export history must not enter the live cache");
            }
            assertEquals(1, broker.accountReads.get(), "Journaling needs orders, not another positions/balances read");
            broker.failAt = broker.orderReads.get() + 3;
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> runtime.exportExecutions(Format.CSV));
            assertTrue(error.getMessage().contains("HTTP 503"));
            assertEquals(before, runtime.view("LATE", "test"), "A failed history read must not publish partial results");
            broker.now.addAndGet(3000); int readsBefore = broker.orderReads.get(); runtime.refreshAccount();
            assertEquals(readsBefore + 1, broker.orderReads.get(), "Live polling stays single-page after a paginated export");
        }
    }

    @Test void aSlowJournalReadDoesNotHoldTheLiveAccountLock() throws Exception {
        Broker broker = new Broker(); ExecutorService worker = Executors.newSingleThreadExecutor(task -> new Thread(task, "journal-export-test"));
        try (TradingRuntime runtime = broker.runtime()) {
            runtime.refreshAccount(); broker.blockJournal = true;
            Future<String> export = worker.submit(() -> runtime.exportExecutions(Format.CSV));
            assertTrue(broker.journalStarted.await(2, TimeUnit.SECONDS));
            broker.now.addAndGet(3000); runtime.refreshAccount();
            assertEquals(2, broker.accountReads.get(), "Live refresh completes while export is waiting on HTTP");
            assertFalse(export.isDone());
            broker.releaseJournal.countDown();
            assertTrue(export.get(3, TimeUnit.SECONDS).contains("EARLY"));
        } finally { broker.releaseJournal.countDown(); worker.shutdownNow(); }
    }
}
