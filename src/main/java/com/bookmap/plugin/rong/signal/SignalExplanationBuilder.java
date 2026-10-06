package com.bookmap.plugin.rong.signal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import com.bookmap.plugin.rong.patterns.PatternEvent;
import com.bookmap.plugin.rong.patterns.PatternEventType;

public final class SignalExplanationBuilder {
    public String build(PatternEvent trigger, TradingSignal.FirstValidation validation, List<ConfirmationMatch> subsequent) {
        StringBuilder text = new StringBuilder(TradingSignal.directionFrom(trigger).name()).append(" SIGNAL (advisory)\n")
                .append("Trigger: ").append(describe(trigger)).append('\n')
                .append("Observed behavior: ").append(behavior(trigger)).append('\n')
                .append("Normal trigger threshold: ").append(size(validation.normalTriggerThreshold)).append('\n')
                .append("Applied trigger threshold: ").append(size(validation.appliedTriggerThreshold)).append('\n')
                .append("Confirmation at first validation: ").append(validation.confirmationStrength).append('\n');
        if (validation.appliedTriggerThreshold < validation.normalTriggerThreshold) {
            text.append("Threshold reduced by ").append(validation.confirmationStrength).append(" complementary offer evidence; the bid trigger is still required.\n");
        } else { text.append("Bid trigger meets the normal requirement; offer confirmation is optional.\n"); }
        for (ConfirmationMatch match : validation.confirmations) appendEvidence(text, trigger, match, "Confirmation");
        for (ConfirmationMatch match : subsequent) appendEvidence(text, trigger, match, "Additional confirmation observed after validation");
        if (!subsequent.isEmpty()) text.append("First validation time and applied threshold are unchanged.\n");
        return text.toString().stripTrailing();
    }
    private void appendEvidence(StringBuilder text, PatternEvent trigger, ConfirmationMatch match, String label) {
        text.append(label).append(": ").append(describe(match.event)).append("; ")
                .append(match.timeDeltaNs).append(" ns (").append(match.timeDeltaMs).append(" ms) relative to trigger; ")
                .append(match.ordering).append("; price distance ").append(match.priceDistanceTicks).append(" ticks; same alias/epoch ")
                .append(trigger.instrumentAlias).append('/').append(trigger.epoch).append('\n')
                .append("Evidence: ").append(behavior(match.event)).append('\n');
    }
    public static String describe(PatternEvent event) {
        String attribution = event.evidence.attribution == PatternEvent.Attribution.UNKNOWN ? "" : "; attribution=" + event.evidence.attribution;
        return event.type.name() + " " + size(event.size) + " @ " + price(event.price) + "; size basis=" + event.sizeBasis + attribution;
    }
    public static String size(long value) {
        if (value < 1000) return value + " shares";
        String shortSize = value % 1000 == 0 ? value / 1000 + "K" : String.format(Locale.ROOT, "%.1fK", value / 1000.0);
        return shortSize + " (" + value + " shares)";
    }
    public static String price(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
    private static String behavior(PatternEvent event) {
        switch (event.type) {
            case BID_REAPPEAR: case BID_STEP_UP:
                return "A persistent bid wall appeared at the same/better defensive price with quotes on the defended side; this is quote/wall defense, not proof of executed absorption.";
            case OFFER_REAPPEAR: case OFFER_STEP_DOWN:
                return "A persistent offer appeared at the same/better defensive price with quotes below it.";
            case BIDS_CANCELLED:
                return "Persistent displayed bids withdrew with little matching observed sell volume and no probable relocation; cancellation is inferred, not confirmed individual-order activity.";
            case BID_BREAKDOWN:
                return "Bid loss was attributed to observed selling, followed by a trade below the bid level.";
            case OFFER_REJECTION:
                return "Price approached the persistent offer from below and rejected downward for the required hold interval.";
            case OFFER_SIZE_INCREASING_REJECTION:
                return "The offer grew during the interaction, then price approached and rejected downward while the offer remained present.";
            case OFFER_BREAKOUT:
                return "Offer loss was attributed to observed buying, followed by a trade above the offer level.";
            case OFFER_SIZE_INCREASE:
                return "Displayed offer size increased; directional price interaction has not been established.";
            default: return "Attribution or directional meaning is unknown.";
        }
    }
}
