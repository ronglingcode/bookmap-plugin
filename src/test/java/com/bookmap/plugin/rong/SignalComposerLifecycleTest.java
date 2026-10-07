package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.SignalComposerActivationTest.*;
import static com.bookmap.plugin.rong.SignalComposerCallbacksTest.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import velox.api.layer1.simplified.Api;
import com.bookmap.plugin.rong.signal.*;

class SignalComposerLifecycleTest {
    @TempDir Path directory;
    private RongPlugin ready() throws Exception {
        Path config = directory.resolve("composer.json"); Files.writeString(config, "{\"enabled\":true}");
        System.setProperty(SignalComposerConfig.CONFIG_PROPERTY, config.toString());
        RongPlugin p = callbackPlugin(); time(p, 0); p.onSnapshotEnd(); p.onBbo(5100, 1, 5121, 1);
        return p;
    }
    private void context(RongPlugin p) {
        time(p, 2000); p.onDepth(false, 5120, 60000); time(p, 2500);
        time(p, 2600); p.onTrade(5119, 1, null); time(p, 2700); p.onTrade(5113, 1, null);
        time(p, 3200); p.onTrade(5113, 1, null);
    }
    @Test void disableClearsAndConsumesNothingThenEnableSeedsCurrentBook() throws Exception {
        withIsolatedActivation(() -> {
            RongPlugin p = ready(); context(p); assertEquals(1, store().snapshot("TEST").contexts.size());
            SignalCompositionPipeline pipeline = (SignalCompositionPipeline)field("signalComposition").get(p);
            p.onIndicatorConfigChanged(IndicatorConfig.SIGNAL_COMPOSER, false); long epoch = pipeline.epoch();
            assertTrue(store().snapshot("TEST").contexts.isEmpty());
            time(p, 3300); p.onDepth(true, 5105, 3000); time(p, 4000);
            assertEquals(epoch, pipeline.epoch()); assertEquals(0, store().snapshot("TEST").marketTimeNs);
            p.onIndicatorConfigChanged(IndicatorConfig.SIGNAL_COMPOSER, true); assertTrue(pipeline.usable());
            time(p, 4500); time(p, 6200); p.onDepth(true, 5105, 0); time(p, 6700);
            assertTrue(store().snapshot("TEST").signals.isEmpty()); // 3K has no old exceptional evidence
            assertEquals(60000, ((OrderBookState)field("orderBook").get(p)).getSizeAt(false, 5120));
        });
    }
    @Test void seekRequiresFreshReadinessEvenAcrossToggleAndSessionClearsContext() throws Exception {
        withIsolatedActivation(() -> {
            RongPlugin p = ready(); context(p); SignalCompositionPipeline pipeline = (SignalCompositionPipeline)field("signalComposition").get(p);
            time(p, 1000); assertTrue(store().snapshot("TEST").contexts.isEmpty()); assertFalse(pipeline.usable());
            assertFalse(field("compositionSnapshotComplete").getBoolean(p));
            p.onIndicatorConfigChanged(IndicatorConfig.SIGNAL_COMPOSER, false); p.onIndicatorConfigChanged(IndicatorConfig.SIGNAL_COMPOSER, true);
            time(p, 1100); assertFalse(pipeline.usable()); p.onSnapshotEnd(); assertTrue(pipeline.usable());
            assertFalse(field("compositionSeedFromSharedBook").getBoolean(p));
            context(p); assertFalse(store().snapshot("TEST").contexts.isEmpty());
            p.onTimestamp(BASE + 6 * 60 * 60 * 1_000_000_000L); // NY close
            assertTrue(store().snapshot("TEST").contexts.isEmpty()); assertFalse(pipeline.usable());
        });
    }
    @Test void stopReleasesAttachmentAndLastRulesThenReattachmentReloads() throws Exception {
        withIsolatedActivation(() -> {
            RongPlugin p = ready(); context(p);
            int previousCount = field("instanceCount").getInt(null);
            field("instanceCount").setInt(null, 1);
            field("api").set(p, Proxy.newProxyInstance(Api.class.getClassLoader(), new Class<?>[]{Api.class}, (proxy, method, args) -> null));
            try {
                SignalComposerConfig previous = (SignalComposerConfig)field("signalComposerConfig").get(null);
                p.stop(); p.stop(); assertNull(field("signalComposition").get(p)); assertNull(field("tradingSignalStore").get(null));
                assertNull(field("signalComposerConfig").get(null));
                Files.writeString(directory.resolve("composer.json"), "{\"enabled\":false}");
                RongPlugin next = callbackPlugin(); assertNotSame(previous, field("compositionRules").get(next));
                assertFalse(field("signalCompositionEnabled").getBoolean(next));
            } finally { field("instanceCount").setInt(null, previousCount); }
        });
    }
}
