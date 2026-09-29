package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.bookmap.LiveMode;
import velox.api.layer1.data.Layer1ApiProviderSupportedFeatures;
import velox.api.layer1.data.Layer1ApiProviderSupportedFeaturesBuilder;
import velox.api.layer1.layers.Layer1ApiRelay;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LiveModeTest {
    static class UnmarkedProvider extends Layer1ApiRelay {
        Layer1ApiProviderSupportedFeatures features = new Layer1ApiProviderSupportedFeaturesBuilder().build();
        long clockOffsetMs;
        UnmarkedProvider() { super(null, false); }
        @Override public long getCurrentTime() { return (System.currentTimeMillis() + clockOffsetMs) * 1_000_000; }
        @Override public Layer1ApiProviderSupportedFeatures getSupportedFeatures() { return features; }
    }
    static class WrappedProvider extends Layer1ApiRelay {
        WrappedProvider(UnmarkedProvider provider) { super(provider, false); }
    }
    @Test void builtInAndWrappedProvidersAreEligibleAfterRealtimeStarts() {
        var live = new UnmarkedProvider();
        assertFalse(LiveMode.isVerifiedLive(live, false));
        assertTrue(LiveMode.isVerifiedLive(live, true));
        assertTrue(LiveMode.isVerifiedLive(new WrappedProvider(live), true));
    }
    @Test void replayDelayedMissingAndStaleClocksCannotExecute() {
        var live = new UnmarkedProvider();
        live.features = new Layer1ApiProviderSupportedFeaturesBuilder().setAdditionalTimeSource(() -> 1L).build();
        assertFalse(LiveMode.isVerifiedLive(live, true));
        live.features = new Layer1ApiProviderSupportedFeaturesBuilder().setDelayed(true).build();
        assertFalse(LiveMode.isVerifiedLive(live, true));
        live.features = new Layer1ApiProviderSupportedFeaturesBuilder().build();
        live.clockOffsetMs = -60_000;
        assertFalse(LiveMode.isVerifiedLive(new WrappedProvider(live), true));
        live.clockOffsetMs = 60_000;
        assertFalse(LiveMode.isVerifiedLive(live, true));
        live.features = null;
        assertFalse(LiveMode.isVerifiedLive(live, true));
        assertFalse(LiveMode.isVerifiedLive(null, true));
    }
}
