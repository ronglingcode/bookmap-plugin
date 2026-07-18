package com.bookmap.plugin.rong.patterns;

@FunctionalInterface
public interface PatternEligibility {
    boolean isEnabled(PatternType patternType);
}
