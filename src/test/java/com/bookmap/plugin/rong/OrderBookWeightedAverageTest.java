package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import velox.api.layer1.simplified.IndicatorModifiable;
import velox.api.layer1.data.InstrumentInfo;

class OrderBookWeightedAverageTest {
    @Test
    void filtersTheObservedExtremeAskAndZeroPriceWithoutChangingTheBook() {
        OrderBookState book = new OrderBookState();
        book.update(true, 8700, 500);
        book.update(false, 8900, 500);
        book.update(false, 10000, 20000);
        double reference = book.getWeightedAverageReferencePriceLevel();
        double expected = book.getWeightedAveragePriceLevel();
        book.update(false, 19999900, 300); // Observed PCVX $199,999 quote, pips=0.01.
        book.update(true, 0, 1000);
        assertEquals(8800, book.getWeightedAverageReferencePriceLevel());
        assertEquals(expected, book.getWeightedAveragePriceLevel(reference * 0.01, reference * 10), 1e-8);
        assertTrue(book.getWeightedAveragePriceLevel() > 100000);
        assertEquals(300, book.getSizeAt(false, 19999900));
        assertEquals(1000, book.getSizeAt(true, 0));
        OrderBookState.WeightedAverageAudit audit = book.auditWeightedAverage(reference * 0.01, reference * 10);
        assertEquals(expected, audit.average, 1e-8);
        assertEquals(1300, audit.excludedQuantity);
        assertEquals(10000, audit.maxPrice);
    }

    @Test
    void boundariesAreInclusiveAndReevaluatedWhenReferenceChanges() {
        OrderBookState book = new OrderBookState();
        book.update(true, 99, 100);
        book.update(true, 100, 20);
        book.update(false, 100000, 30);
        book.update(false, 100001, 100);
        assertEquals((100.0 * 20 + 100000.0 * 30) / 50,
                book.getWeightedAveragePriceLevel(100, 100000), 1e-8);
        assertEquals(99, book.getWeightedAveragePriceLevel(99, 99));
        assertTrue(Double.isNaN(book.getWeightedAveragePriceLevel(99.1, 99.9)));
        assertTrue(Double.isNaN(book.getWeightedAveragePriceLevel(Double.NaN, Double.NaN)));
        assertEquals(book.auditWeightedAverage(200, 200000).average,
                book.getWeightedAveragePriceLevel(200, 200000), 1e-8);
        book.update(false, 100001, 0);
        assertEquals(book.auditWeightedAverage(100, 100000).average,
                book.getWeightedAveragePriceLevel(100, 100000), 1e-8);
    }

    @Test
    void combinesAllLevelsAndTreatsUpdatesAsAbsoluteQuantities() {
        OrderBookState book = new OrderBookState();
        assertTrue(Double.isNaN(book.getWeightedAveragePriceLevel()));
        book.update(true, 100, 10);
        book.update(true, 90, 30);
        book.update(false, 110, 20);
        book.update(false, 200, 40); // Far-away liquidity also contributes.
        assertEquals(139, book.getWeightedAveragePriceLevel(), 1e-10);
        book.update(false, 200, 10);
        assertEquals(7900.0 / 70, book.getWeightedAveragePriceLevel(), 1e-10);
        book.update(false, 200, 0);
        assertEquals(5900.0 / 60, book.getWeightedAveragePriceLevel(), 1e-10);
        book.update(false, 110, 0);
        assertEquals(92.5, book.getWeightedAveragePriceLevel(), 1e-10);
        book.update(true, 90, 0);
        book.update(true, 100, 0);
        assertTrue(Double.isNaN(book.getWeightedAveragePriceLevel()));
        book.update(false, 111, 5);
        assertEquals(111, book.getWeightedAveragePriceLevel());
    }

    @Test
    void quantitiesAndProductsDoNotOverflowIntegers() {
        OrderBookState book = new OrderBookState();
        book.update(true, 2_000_000_000, Integer.MAX_VALUE);
        book.update(false, 2_000_000_002, Integer.MAX_VALUE);
        assertEquals(2_000_000_001.0, book.getWeightedAveragePriceLevel(), 1e-6);
        assertThrows(IllegalArgumentException.class, () -> book.update(true, 2_000_000_000, -1));
        assertEquals(2_000_000_001.0, book.getWeightedAveragePriceLevel(), 1e-6);
    }

    @Test
    void incrementalAverageMatchesAnIndependentFullBookCalculation() {
        OrderBookState book = new OrderBookState();
        Random random = new Random(41);
        long[][] quantities = new long[2][100];
        for (int i = 0; i < 10_000; i++) {
            int side = random.nextInt(2), price = random.nextInt(100);
            int size = random.nextInt(4) == 0 ? 0 : random.nextInt(1_000_000);
            quantities[side][price] = size;
            book.update(side == 0, price + 100, size);
            long total = 0;
            double weighted = 0;
            for (long[] levels : quantities) {
                for (int p = 0; p < levels.length; p++) {
                    total += levels[p];
                    weighted += (p + 100.0) * levels[p];
                }
            }
            assertEquals(total == 0 ? Double.NaN : weighted / total,
                    book.getWeightedAveragePriceLevel(), 1e-9);
        }
    }

    @Test
    void runtimeAuditReportsActualBookRangeAndLargestContributors() {
        OrderBookState book = new OrderBookState();
        assertTrue(Double.isNaN(book.auditWeightedAverage().average));
        book.update(true, 8700, 500);
        book.update(false, 10000, 20000);
        book.update(false, 20000, 100000);
        OrderBookState.WeightedAverageAudit audit = book.auditWeightedAverage();
        assertEquals(book.getWeightedAveragePriceLevel(), audit.average, 1e-9);
        assertEquals(500, audit.bidQuantity);
        assertEquals(120000, audit.askQuantity);
        assertEquals(8700, audit.minPrice);
        assertEquals(20000, audit.maxPrice);
        assertTrue(audit.average >= audit.minPrice && audit.average <= audit.maxPrice);
        assertEquals(20000, audit.largestContributions.get(0).price);
        assertFalse(audit.largestContributions.get(0).bid);
        book.update(false, 20000, 0);
        audit = book.auditWeightedAverage();
        assertEquals(10000, audit.maxPrice);
        assertEquals(book.getWeightedAveragePriceLevel(), audit.average, 1e-9);
        assertEquals(10000, audit.largestContributions.get(0).price);
    }

    @Test
    void chartWaitsForSnapshotUsesTicksAndHandlesTogglesAndEmptyBook() throws Exception {
        List<Double> points = new ArrayList<>();
        List<Long> timestamps = new ArrayList<>();
        List<Color> colors = new ArrayList<>();
        IndicatorModifiable indicator = (IndicatorModifiable) Proxy.newProxyInstance(
                IndicatorModifiable.class.getClassLoader(), new Class<?>[]{IndicatorModifiable.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addPoint")) {
                        assertEquals(2, args.length, "Use the same timestamped API as VWAP");
                        timestamps.add(((Number) args[0]).longValue());
                        points.add(((Number) args[1]).doubleValue());
                    } else if (method.getName().equals("setColor")) {
                        colors.add((Color) args[0]);
                    }
                    return null;
                });
        RongPlugin plugin = new RongPlugin();
        IndicatorConfig config = new IndicatorConfig();
        assertFalse(config.isEnabled(IndicatorConfig.ORDER_BOOK_WEIGHTED_AVERAGE));
        config.setEnabled(IndicatorConfig.ORDER_BOOK_WEIGHTED_AVERAGE, true);
        Field configField = field("indicatorConfig");
        Object previousConfig = configField.get(null);
        try {
            configField.set(null, config);
            field("initialized").set(plugin, true);
            field("orderBook").set(plugin, new OrderBookState());
            field("alias").set(plugin, "TEST");
            field("instrumentInfo").set(plugin,
                    new InstrumentInfo("TEST", "NASDAQ", "STOCKS", 0.01, 1, "TEST", true));
            field("bookAverageIndicator").set(plugin, indicator);
            field("lastTimestampNs").set(plugin, 123_000L);
            plugin.onDepth(true, 10000, 10);
            plugin.onDepth(false, 10020, 30);
            assertTrue(points.isEmpty());
            plugin.onSnapshotEnd();
            assertEquals(10015, points.get(0));
            assertEquals(123_000L, timestamps.get(0));
            plugin.onDepth(false, 19999900, 300);
            assertEquals(10015, points.get(points.size() - 1), 1e-8);
            points.remove(points.size() - 1);
            timestamps.remove(timestamps.size() - 1);
            plugin.onDepth(false, 19999900, 0);
            points.remove(points.size() - 1);
            timestamps.remove(timestamps.size() - 1);
            plugin.onTimestamp(124_000L);
            plugin.onDepth(false, 10020, 10);
            assertEquals(10010, points.get(1));
            assertEquals(124_000L, timestamps.get(1));
            config.setEnabled(IndicatorConfig.ORDER_BOOK_WEIGHTED_AVERAGE, false);
            plugin.onIndicatorConfigChanged(IndicatorConfig.ORDER_BOOK_WEIGHTED_AVERAGE, false);
            plugin.onDepth(true, 10000, 0);
            assertEquals(2, points.size());
            assertEquals(0, colors.get(0).getAlpha());
            config.setEnabled(IndicatorConfig.ORDER_BOOK_WEIGHTED_AVERAGE, true);
            plugin.onIndicatorConfigChanged(IndicatorConfig.ORDER_BOOK_WEIGHTED_AVERAGE, true);
            assertEquals(new Color(0, 229, 255), colors.get(1));
            plugin.onDepth(false, 10020, 0);
            assertTrue(Double.isNaN(points.get(2)));
            plugin.onDepth(true, 9990, 5);
            assertEquals(9990, points.get(3));
        } finally {
            configField.set(null, previousConfig);
        }
    }

    private static Field field(String name) throws Exception {
        Field field = RongPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
