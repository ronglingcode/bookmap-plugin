package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.bookmap.LiveMode;
import velox.api.layer0.annotations.Layer0LiveModule;
import velox.api.layer1.data.Layer1ApiProviderSupportedFeatures;
import velox.api.layer1.data.Layer1ApiProviderSupportedFeaturesBuilder;
import velox.api.layer1.layers.Layer1ApiRelay;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LiveModeTest {
    static class UnknownProvider extends Layer1ApiRelay {
        Layer1ApiProviderSupportedFeatures features = new Layer1ApiProviderSupportedFeaturesBuilder().build();
        UnknownProvider() { super(null, false); }
        @Override public long getCurrentTime() { return System.currentTimeMillis() * 1_000_000; }
        @Override public Layer1ApiProviderSupportedFeatures getSupportedFeatures() { return features; }
    }
    @Layer0LiveModule(fullName="Fake Live", shortName="Fake", localizationKey="fake")
    static class LiveProvider extends UnknownProvider { }
    @Test void onlyVerifiedLiveNonDelayedProvidersAreEligible() {
        assertFalse(LiveMode.isVerifiedLive(new UnknownProvider()));
        var live = new LiveProvider(); assertTrue(LiveMode.isVerifiedLive(live));
        live.features = new Layer1ApiProviderSupportedFeaturesBuilder().setAdditionalTimeSource(() -> 1L).build();
        assertFalse(LiveMode.isVerifiedLive(live));
        live.features = new Layer1ApiProviderSupportedFeaturesBuilder().setDelayed(true).build();
        assertFalse(LiveMode.isVerifiedLive(live));
        assertFalse(LiveMode.isVerifiedLive(null));
    }
}
