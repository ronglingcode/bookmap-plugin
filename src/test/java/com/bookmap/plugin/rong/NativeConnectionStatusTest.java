package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NativeConnectionStatusTest {
    @Test
    void reportsHistoryAndStreamingIndependently() {
        NativeConnectionStatus status = new NativeConnectionStatus();
        AtomicReference<NativeConnectionStatus.Snapshot> observed = new AtomicReference<>();
        status.addListener(observed::set);

        status.setStarting();
        assertFalse(observed.get().areAllConnected());
        status.update("massiveHistory", "ready");
        status.update("massiveStream", "connected; waiting for trades");
        assertTrue(observed.get().isMassiveHistoryReady());
        assertFalse(observed.get().isMassiveStreamReceiving());
        status.update("schwab", "connected");
        assertFalse(observed.get().areAllConnected());
        status.update("massiveStream", "receiving trades");

        assertTrue(observed.get().areAllConnected());
        assertEquals("ready", observed.get().getMassiveHistory());
        assertEquals("receiving trades", observed.get().getMassiveStream());
    }
}
