package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OrderBookStateConcurrencyTest {
    @Test void emptyBookAndRetainedSnapshotsRemainSafeAfterRemoval() {
        OrderBookState book = new OrderBookState();
        assertNull(book.getBestBid());
        assertNull(book.getBestAsk());
        assertEquals(0, book.getBestBidSize());
        assertEquals(0, book.getBestAskSize());
        assertEquals(0, book.getPercentileThreshold(90));
        book.update(true, 100, 20);
        book.update(false, 101, 30);
        var bids = book.getBids();
        var asks = book.getAsks();
        book.update(true, 100, 0);
        book.update(false, 101, 0);
        assertEquals(20, bids.get(100));
        assertEquals(30, asks.get(101));
        assertThrows(UnsupportedOperationException.class, () -> bids.clear());
        assertTrue(book.getBids().isEmpty());
        assertTrue(book.getAsks().isEmpty());
    }

    @Test void concurrentReadersTolerateRepeatedRemovalOfTheLastLevel() throws Exception {
        OrderBookState book = new OrderBookState();
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var writer = executor.submit(() -> {
                start.await();
                for (int i = 0; i < 20_000; i++) {
                    book.update(true, 100, 20);
                    book.update(false, 101, 30);
                    book.update(true, 100, 0);
                    book.update(false, 101, 0);
                }
                return null;
            });
            var reader = executor.submit(() -> {
                start.await();
                for (int i = 0; i < 20_000; i++) {
                    Integer bid = book.getBestBid(), ask = book.getBestAsk();
                    assertTrue(bid == null || bid == 100);
                    assertTrue(ask == null || ask == 101);
                    assertTrue(book.getBestBidSize() >= 0);
                    assertTrue(book.getBestAskSize() >= 0);
                    assertTrue(book.getPercentileThreshold(90) >= 0);
                    book.getBidDepth(10);
                    book.getAskDepth(10);
                    book.getTotalBidSize();
                    book.getTotalAskSize();
                    book.getBids().forEach((price, size) -> assertEquals(20, size));
                    book.getAsks().forEach((price, size) -> assertEquals(30, size));
                }
                return null;
            });
            start.countDown();
            writer.get(20, TimeUnit.SECONDS);
            reader.get(20, TimeUnit.SECONDS);
            assertEquals(0, book.getPercentileThreshold(90));
        } finally {
            executor.shutdownNow();
        }
    }
}
