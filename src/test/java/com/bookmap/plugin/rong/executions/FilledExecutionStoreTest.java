package com.bookmap.plugin.rong.executions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class FilledExecutionStoreTest {

    private static final String INSTRUMENT = "TEST";

    @Test
    void transientLabelsDisappearAfterThirtySeconds() {
        FilledExecutionStore store = new FilledExecutionStore();
        long nowNs = 1_000_000_000_000L;
        FilledExecutionMarker recent = marker(nowNs - 1_000_000_000L);
        FilledExecutionMarker atBoundary =
                marker(nowNs - FilledExecutionStore.TRANSIENT_DISPLAY_TTL_NS);
        FilledExecutionMarker expired =
                marker(nowNs - FilledExecutionStore.TRANSIENT_DISPLAY_TTL_NS - 3_000_000_000L);
        store.replaceAll(INSTRUMENT, Arrays.asList(expired, atBoundary, recent));

        List<FilledExecutionMarker> displayed =
                store.getMarkersForDisplay(INSTRUMENT, false, nowNs);

        assertEquals(2, displayed.size());
        assertSame(atBoundary, displayed.get(0));
        assertSame(recent, displayed.get(1));
    }

    @Test
    void persistentLabelsIgnoreExecutionAge() {
        FilledExecutionStore store = new FilledExecutionStore();
        long nowNs = 1_000_000_000_000L;
        FilledExecutionMarker expired =
                marker(nowNs - FilledExecutionStore.TRANSIENT_DISPLAY_TTL_NS - 1L);
        store.replaceAll(INSTRUMENT, Arrays.asList(expired));

        List<FilledExecutionMarker> displayed =
                store.getMarkersForDisplay(INSTRUMENT, true, nowNs);

        assertEquals(1, displayed.size());
        assertSame(expired, displayed.get(0));
    }

    @Test
    void groupsUnsortedSameSideFillsAtWeightedPriceAndTotalShares() {
        FilledExecutionStore store = new FilledExecutionStore();
        long start = 1_000_000_000_000L;
        FilledExecutionMarker first = fill(163.46, 20, false, true, start);
        FilledExecutionMarker second = fill(163.45, 18, false, false, start + 1_000_000_000L);
        store.replaceAll(INSTRUMENT, Arrays.asList(second, first));

        List<FilledExecutionMarker> displayed = store.getMarkersForDisplay(INSTRUMENT, true, start);

        assertEquals(1, displayed.size());
        FilledExecutionMarker group = displayed.get(0);
        double expected = (20 * 163.46 + 18 * 163.45) / 38;
        assertEquals(38, group.getQuantity());
        assertEquals(expected, group.getRealPrice(), 1e-10);
        assertEquals(expected / 0.01, group.getPriceInTicks(), 1e-8);
        assertEquals(second.getTimeNs(), group.getTimeNs());
        assertEquals(false, group.isBuy());
        assertEquals(true, group.isOpening());
        assertEquals(2, store.getMarkers(INSTRUMENT).size());
    }

    @Test
    void keepsSidesSeparateAndLimitsEntireGroupToTwoSeconds() {
        FilledExecutionStore store = new FilledExecutionStore();
        long start = 1_000_000_000_000L;
        FilledExecutionMarker sell = fill(11, 7, false, false, start + 1_000_000_000L);
        FilledExecutionMarker outside = fill(12, 3, true, false, start + 2_000_000_001L);
        store.replaceAll(INSTRUMENT, Arrays.asList(
                fill(10, 0.5, true, true, start), sell,
                fill(11, 1.5, true, true, start + FilledExecutionStore.GROUP_WINDOW_NS), outside));

        List<FilledExecutionMarker> displayed = store.getMarkersForDisplay(INSTRUMENT, true, start);

        assertEquals(3, displayed.size());
        assertSame(sell, displayed.get(0));
        assertEquals(2, displayed.get(1).getQuantity());
        assertEquals(10.75, displayed.get(1).getRealPrice());
        assertSame(outside, displayed.get(2));
    }

    @Test
    void transientGroupKeepsAllSharesUntilThirtySecondsAfterLatestFill() {
        FilledExecutionStore store = new FilledExecutionStore();
        long start = 1_000_000_000_000L;
        long latest = start + 1_000_000_000L;
        store.replaceAll(INSTRUMENT, Arrays.asList(marker(start), marker(latest)));
        long expires = latest + FilledExecutionStore.TRANSIENT_DISPLAY_TTL_NS;

        assertEquals(2, store.getMarkersForDisplay(INSTRUMENT, false, expires).get(0).getQuantity());
        assertEquals(0, store.getMarkersForDisplay(INSTRUMENT, false, expires + 1).size());
    }

    @Test
    void replacementRecomputesGroupsWithoutAccumulatingSharesAndIsolatesInstruments() {
        FilledExecutionStore store = new FilledExecutionStore();
        long start = 1_000_000_000_000L;
        List<FilledExecutionMarker> fills = Arrays.asList(marker(start), marker(start + 1));
        store.replaceAll(INSTRUMENT, fills);
        store.replaceAll(INSTRUMENT, fills);
        store.replaceAll("OTHER", Arrays.asList(
                new FilledExecutionMarker("OTHER", 100, 10, 5, true, true, start)));

        assertEquals(2, store.getMarkersForDisplay(INSTRUMENT, true, start).get(0).getQuantity());
        assertEquals(5, store.getMarkersForDisplay("OTHER", true, start).get(0).getQuantity());
    }

    private static FilledExecutionMarker fill(
            double price, double quantity, boolean buy, boolean opening, long timeNs) {
        return new FilledExecutionMarker(INSTRUMENT, price / 0.01, price, quantity, buy, opening, timeNs);
    }

    private static FilledExecutionMarker marker(long timeNs) {
        return new FilledExecutionMarker(INSTRUMENT, 100, 10, 1, true, true, timeNs);
    }
}
