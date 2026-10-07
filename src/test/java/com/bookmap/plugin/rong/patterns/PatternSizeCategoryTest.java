package com.bookmap.plugin.rong.patterns;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PatternSizeCategoryTest {
    @Test void allBandsHaveExactInclusiveLowerBoundaries() {
        PatternSizeCategory[] bands = PatternSizeCategory.values();
        for (int i = 1; i < bands.length; i++) {
            assertEquals(bands[i - 1], PatternSizeCategory.classify(bands[i].minimumSize - 1));
            assertEquals(bands[i], PatternSizeCategory.classify(bands[i].minimumSize));
        }
        assertEquals(PatternSizeCategory.UNTRACKED, PatternSizeCategory.classify(0));
        assertEquals(PatternSizeCategory.EXCEPTIONAL, PatternSizeCategory.classify(Long.MAX_VALUE));
    }
    @Test void everyPatternTypeUsesItsMeasuredSizeForCategory() {
        for (PatternEventType type : PatternEventType.values()) {
            PatternEvent small = event(type, 2999, 1);
            PatternEvent promoted = event(type, 3000, 2);
            assertEquals(PatternSizeCategory.LEAST_SIGNIFICANT, small.sizeCategory);
            assertEquals(PatternSizeCategory.BELOW_NORMAL, promoted.sizeCategory);
            assertEquals(small.id, promoted.id);
            assertEquals(small.meaning, promoted.meaning);
        }
    }
    private PatternEvent event(PatternEventType type, long size, int revision) {
        return PatternEvent.builder("TEST", 1, type, "wall").revision(revision)
                .size(size, PatternEvent.SizeBasis.DISPLAYED_WALL).price(5105, .01).times(100, 100 + revision).build();
    }
}
