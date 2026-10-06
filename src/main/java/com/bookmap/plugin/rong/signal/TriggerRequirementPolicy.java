package com.bookmap.plugin.rong.signal;

import java.util.Objects;

public final class TriggerRequirementPolicy {
    private final SignalComposerConfig config;
    public TriggerRequirementPolicy(SignalComposerConfig config) { this.config = config; }
    public long requiredSize(ConfirmationStrength strength) {
        switch (Objects.requireNonNull(strength)) {
            case NONE: return config.triggerRequirements.none;
            case BELOW_NORMAL: return config.triggerRequirements.belowNormal;
            case NORMAL: return config.triggerRequirements.normal;
            case STRONG: return config.triggerRequirements.strong;
            case VERY_STRONG: return config.triggerRequirements.veryStrong;
            case EXCEPTIONAL: return config.triggerRequirements.exceptional;
            default: throw new IllegalArgumentException("Unsupported confirmation strength");
        }
    }
    public boolean accepts(long triggerSize, ConfirmationStrength strength) {
        return triggerSize >= config.minimumTriggerSize && triggerSize >= requiredSize(strength);
    }
}
