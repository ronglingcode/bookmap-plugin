package com.bookmap.plugin.rong.miniviteapp.bookmap;

import velox.api.layer1.Layer1ApiProvider;
import com.bookmap.plugin.rong.miniviteapp.config.ExecutionConfig;

/** Use the realtime lifecycle and provider clock, rather than external-addon class markers. */
public final class LiveMode {
    private LiveMode() { }
    public static boolean isVerifiedLive(Layer1ApiProvider provider, boolean realtimeStarted) {
        return getBlockReason(provider, realtimeStarted).isEmpty();
    }
    public static String getBlockReason(Layer1ApiProvider provider, boolean realtimeStarted) {
        if (!realtimeStarted) return "Bookmap historical loading has not finished";
        if (provider == null) return "Bookmap data provider is unavailable";
        try {
            var features = provider.getSupportedFeatures();
            if (features == null) return "Bookmap provider features are unavailable";
            if (features.additionalTimeSource != null) return "Bookmap uses an additional replay time source";
            if (features.isDelayed) return "Bookmap data provider is delayed";
            long providerTimeMs = provider.getCurrentTime() / 1_000_000;
            long now = System.currentTimeMillis();
            if (providerTimeMs < now - ExecutionConfig.MAX_STATE_AGE_MS || providerTimeMs > now + 1000)
                return "Bookmap clock is not current (replay, paused feed, or clock mismatch)";
            return "";
        } catch (RuntimeException error) { return "Bookmap provider live status could not be read"; }
    }
}
