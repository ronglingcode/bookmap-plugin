package com.bookmap.plugin.rong.signal;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

public final class ConfirmationStrengthClassifier {
    private final SignalComposerConfig config;
    public ConfirmationStrengthClassifier(SignalComposerConfig config) { this.config = config; }
    public ConfirmationStrength classify(long size) {
        if (size <= 0) return ConfirmationStrength.NONE;
        if (atLeast(size, config.strengthMultiples.exceptional)) return ConfirmationStrength.EXCEPTIONAL;
        if (atLeast(size, config.strengthMultiples.veryStrong)) return ConfirmationStrength.VERY_STRONG;
        if (atLeast(size, config.strengthMultiples.strong)) return ConfirmationStrength.STRONG;
        if (atLeast(size, config.strengthMultiples.normal)) return ConfirmationStrength.NORMAL;
        return ConfirmationStrength.BELOW_NORMAL;
    }
    private boolean atLeast(long size, double multiple) {
        // Exact comparison avoids integer division, overflow and rounding a huge size across a band.
        BigDecimal boundary = BigDecimal.valueOf(config.normalConfirmationSize).multiply(BigDecimal.valueOf(multiple));
        return BigDecimal.valueOf(size).compareTo(boundary) >= 0;
    }
    public Selection select(List<ConfirmationMatch> matches) {
        Comparator<ConfirmationMatch> order = Comparator.comparingInt((ConfirmationMatch m) -> classify(m.event.size).ordinal()).reversed()
                .thenComparing(Comparator.comparingLong((ConfirmationMatch m) -> m.event.size).reversed())
                .thenComparingLong(m -> Math.abs(m.timeDeltaNs)).thenComparing(m -> m.event.id);
        ConfirmationMatch strongest = matches.stream().min(order).orElse(null);
        return new Selection(strongest == null ? ConfirmationStrength.NONE : classify(strongest.event.size), strongest);
    }
    public static final class Selection {
        public final ConfirmationStrength strength;
        public final ConfirmationMatch strongest;
        private Selection(ConfirmationStrength strength, ConfirmationMatch strongest) { this.strength = strength; this.strongest = strongest; }
    }
}
