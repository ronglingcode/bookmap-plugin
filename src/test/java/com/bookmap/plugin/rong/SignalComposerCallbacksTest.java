package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.SignalComposerActivationTest.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import velox.api.layer1.data.InstrumentInfo;
import com.bookmap.plugin.rong.signal.*;
import com.bookmap.plugin.rong.patterns.*;

class SignalComposerCallbacksTest {
    @TempDir Path directory;
    static final long BASE = Instant.parse("2026-10-06T14:00:00Z").getEpochSecond() * 1_000_000_000L;
    static RongPlugin callbackPlugin() throws Exception {
        RongPlugin p = plugin("TEST");
        field("instrumentInfo").set(p, new InstrumentInfo("TEST", "NASDAQ", "STOCKS", .01, 1, "TEST", true));
        field("orderBook").set(p, new OrderBookState()); field("initialized").setBoolean(p, true);
        p.initializeSignalComposition(.01);
        field("compositionLog").set(p, new SignalCompositionLog("TEST", 2048, new SignalCompositionLog.Sink() {
            public void summary(String alias, String full, String concise) { }
            public void detail(String alias, String full) { }
        }));
        return p;
    }
    static void time(RongPlugin p, long ms) { p.onTimestamp(BASE + ms * 1_000_000L); }
    void enableConfig() throws Exception {
        Path config = directory.resolve("composer.json"); Files.writeString(config, "{\"enabled\":true}");
        System.setProperty(SignalComposerConfig.CONFIG_PROPERTY, config.toString());
    }
    static TradingSignalStore store() throws Exception { return (TradingSignalStore)field("tradingSignalStore").get(null); }
    @Test void callbacksComposeWithLegacyOffAndNoTradebookOrNativeRuntime() throws Exception {
        withIsolatedActivation(() -> {
            enableConfig(); RongPlugin p = callbackPlugin(); time(p, 0); p.onSnapshotEnd(); p.onBbo(5100, 1, 5121, 1);
            time(p, 2000); p.onDepth(false, 5120, 48000); p.onDepth(true, 5105, 3000); time(p, 2500);
            time(p, 2600); p.onDepth(false, 5120, 60000); time(p, 2700); p.onTrade(5119, 1, null);
            time(p, 2800); p.onTrade(5113, 1, null); time(p, 3300); p.onTrade(5113, 1, null);
            assertTrue(store().snapshot("TEST").signals.isEmpty());
            assertEquals(3000, store().snapshot("TEST").contexts.get(Direction.SHORT).requiredTriggerSize);
            time(p, 3400); p.onDepth(true, 5105, 0); time(p, 3900);
            assertEquals(1, store().snapshot("TEST").signals.size());
            assertEquals(Direction.SHORT, store().snapshot("TEST").signals.get(0).direction);
            assertThrows(NoSuchFieldException.class, () -> field("patternEngine"));
            assertNull(field("nativeTrading").get(null));
            assertEquals(0, ((OrderBookState)field("orderBook").get(p)).getSizeAt(true, 5105));
        });
    }
    @Test void preSnapshotAndFallbackCallbacksCannotProduceSemanticEvents() throws Exception {
        withIsolatedActivation(() -> {
            enableConfig(); RongPlugin p = callbackPlugin(); time(p, 0);
            time(p, 2000); p.onDepth(true, 5100, 6000); time(p, 2500); time(p, 3000); p.onDepth(true, 5100, 0); time(p, 3500);
            assertTrue(store().snapshot("TEST").signals.isEmpty());
            field("lastTimestampNs").setLong(p, 0); p.onSnapshotEnd(); p.onDepth(true, 5100, 6000); p.onDepth(true, 5100, 0);
            assertTrue(store().snapshot("TEST").signals.isEmpty());
            assertEquals(0, store().snapshot("TEST").marketTimeNs);
        });
    }
}
