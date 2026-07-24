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
                marker(nowNs - FilledExecutionStore.TRANSIENT_DISPLAY_TTL_NS - 1L);
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

    private static FilledExecutionMarker marker(long timeNs) {
        return new FilledExecutionMarker(INSTRUMENT, 100, 10, 1, true, true, timeNs);
    }
}
