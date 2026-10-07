package com.bookmap.plugin.rong.patterns;

/** Absolute equity-share size bands, independent of directional meaning and signal eligibility. */
public enum PatternSizeCategory {
    UNTRACKED("Below tracking floor", 0),
    LEAST_SIGNIFICANT("Least significant", 1000),
    BELOW_NORMAL("Below normal", 3000),
    NORMAL("Normal", 5000),
    STRONG("Strong", 10000),
    VERY_STRONG("Very strong", 25000),
    EXCEPTIONAL("Exceptional", 50000);

    public static final long MIN_TRACKED_SIZE = LEAST_SIGNIFICANT.minimumSize;
    public final String label;
    public final long minimumSize;

    PatternSizeCategory(String label, long minimumSize) {
        this.label = label; this.minimumSize = minimumSize;
    }

    public static PatternSizeCategory classify(long size) {
        PatternSizeCategory[] categories = values();
        for (int i = categories.length - 1; i > 0; i--) {
            if (size >= categories[i].minimumSize) return categories[i];
        }
        return UNTRACKED;
    }
}
