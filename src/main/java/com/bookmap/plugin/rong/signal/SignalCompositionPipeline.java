package com.bookmap.plugin.rong.signal;

import java.util.function.Consumer;
import java.util.function.LongSupplier;
import com.bookmap.plugin.rong.patterns.PatternEvent;
import com.bookmap.plugin.rong.patterns.PatternObservationEngine;

/** One attachment's serialized callback-to-advisory path. No execution dependencies. */
public final class SignalCompositionPipeline {
    private final SignalComposer composer;
    private final PatternObservationEngine observer;
    private final Consumer<CompositionUpdate> output;
    private int bid, ask, last;
    private PatternEvent latestEvent;
    private String latestDiagnostic = "None", latestReset = "None";
    private Consumer<ResetReason> resetListener = reason -> {};
    public SignalCompositionPipeline(String alias, double pips, SignalComposerConfig config, Consumer<CompositionUpdate> output) {
        this(alias, pips, config, output, event -> {}, diagnostic -> {}, System::currentTimeMillis);
    }
    public SignalCompositionPipeline(String alias, double pips, SignalComposerConfig config, Consumer<CompositionUpdate> output,
            Consumer<PatternEvent> rawEvents, Consumer<String> diagnostics, LongSupplier receiptClock) {
        this.output = update -> {
            if (!update.diagnostics.isEmpty()) latestDiagnostic = update.diagnostics.get(update.diagnostics.size() - 1);
            output.accept(update);
        };
        composer = new SignalComposer(alias, 1, config, receiptClock);
        observer = new PatternObservationEngine(alias, pips, config, event -> {
            latestEvent = event; rawEvents.accept(event); this.output.accept(composer.onPatternEvent(event));
        }, (reason, epoch) -> {
            bid = ask = last = 0; latestEvent = null; latestReset = reason.name(); latestDiagnostic = "None";
            resetListener.accept(reason); this.output.accept(composer.reset(reason, epoch));
        }, message -> { latestDiagnostic = message; diagnostics.accept(message); });
    }
    public void onDepth(boolean bid, int price, long size, long timeNs, PatternEvent.TimestampProvenance provenance) {
        observer.onDepth(bid, price, size, timeNs, provenance); finish(provenance);
    }
    public void onTrade(int price, long size, Boolean buy, long timeNs, PatternEvent.TimestampProvenance provenance) {
        observer.onTrade(price, size, buy, timeNs, provenance);
        if (observer.usable() && provenance == PatternEvent.TimestampProvenance.MARKET) last = price;
        finish(provenance);
    }
    public void onBbo(int bid, int ask, long timeNs, PatternEvent.TimestampProvenance provenance) {
        observer.onBbo(bid, ask, timeNs, provenance);
        if (observer.usable() && provenance == PatternEvent.TimestampProvenance.MARKET && !(bid > 0 && ask > 0 && bid > ask)) {
            this.bid = bid; this.ask = ask;
        }
        finish(provenance);
    }
    public void onTimestamp(long timeNs, PatternEvent.TimestampProvenance provenance) {
        observer.onTimestamp(timeNs, provenance); finish(provenance);
    }
    private void finish(PatternEvent.TimestampProvenance provenance) {
        if (!observer.usable() || provenance != PatternEvent.TimestampProvenance.MARKET) return;
        output.accept(composer.onMarketTime(observer.nowNs()));
        output.accept(composer.onMarketPrice(bid, ask, last));
    }
    public void markReady() { observer.markReady(); }
    public void setResetListener(Consumer<ResetReason> listener) { resetListener = listener; }
    public void reset(ResetReason reason) { observer.reset(reason); }
    public long epoch() { return observer.epoch(); }
    public boolean usable() { return observer.usable(); }
    /** Polling this snapshot does not expire candidates or advance market time. */
    public String inspectionText() {
        String time = observer.nowNs() <= 0 ? "Unavailable" : java.time.Instant.ofEpochSecond(
                observer.nowNs() / 1_000_000_000L, observer.nowNs() % 1_000_000_000L)
                .atZone(java.time.ZoneId.of("America/New_York"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS 'NY'"));
        StringBuilder text = new StringBuilder(observer.status()).append(" · Epoch ").append(observer.epoch())
                .append("\nMarket time: ").append(time).append("\nLatest observation: ");
        if (latestEvent == null) text.append("None since reset");
        else text.append(latestEvent.type).append(" · ").append(latestEvent.meaning).append(" · ")
                .append(latestEvent.size).append(" @ ").append(SignalExplanationBuilder.price(latestEvent.price))
                .append(" · ").append(latestEvent.evidence.coverage).append(" / ").append(latestEvent.evidence.attribution);
        for (String line : composer.inspectionLines()) text.append('\n').append(line);
        return text.append("\nLast reset: ").append(latestReset).append("\nLatest diagnostic: ")
                .append(latestDiagnostic).toString();
    }
}
