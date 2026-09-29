package com.bookmap.plugin.rong.miniviteapp.bookmap;

import velox.api.layer0.annotations.Layer0LiveModule;
import velox.api.layer0.live.ExternalLiveBaseProvider;
import velox.api.layer1.Layer1ApiProvider;
import velox.api.layer1.layers.Layer1ApiTimeSource;

/** Positive live-provider identification. Unknown and embedded/replay time sources are blocked. */
public final class LiveMode {
    private LiveMode() { }
    public static boolean isVerifiedLive(Layer1ApiProvider provider) {
        try {
            var features = provider.getSupportedFeatures();
            if (features == null || features.additionalTimeSource != null || features.isDelayed) return false;
            Layer1ApiProvider source = Layer1ApiTimeSource.getTimeSource(provider);
            return source instanceof ExternalLiveBaseProvider || source != null
                    && source.getClass().isAnnotationPresent(Layer0LiveModule.class);
        } catch (RuntimeException error) { return false; }
    }
}
