package com.bookmap.plugin.rong;

import velox.api.layer1.common.RunModeHelper;

public enum BookmapRunMode {
    LIVE("LIVE", "live"), LIVE_SIMULATED("LIVE DATA / SIMULATED", "live"),
    REPLAY("REPLAY", "replay"), UNKNOWN("MODE UNAVAILABLE", "unknown");

    public final String banner;
    public final String dataMode;

    BookmapRunMode(String banner, String dataMode) {
        this.banner = banner;
        this.dataMode = dataMode;
    }

    static BookmapRunMode detect(String configuredMode) {
        try {
            if (RunModeHelper.isSet()) {
                BookmapRunMode detected = fromFlags(RunModeHelper.isLive(), RunModeHelper.isRealTrading());
                if (detected != UNKNOWN) return detected;
            }
        } catch (LinkageError unavailable) {
            // Older hosts may not expose the helper.
        }
        return "replay".equals(configuredMode) ? REPLAY : UNKNOWN;
    }

    static BookmapRunMode fromFlags(Boolean live, Boolean realTrading) {
        if (Boolean.FALSE.equals(live)) return REPLAY;
        if (!Boolean.TRUE.equals(live) || realTrading == null) return UNKNOWN;
        return realTrading ? LIVE : LIVE_SIMULATED;
    }
}
