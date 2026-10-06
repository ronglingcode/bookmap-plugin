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
    public SignalCompositionPipeline(String alias, double pips, SignalComposerConfig config, Consumer<CompositionUpdate> output) {
        this(alias, pips, config, output, event -> {}, diagnostic -> {}, System::currentTimeMillis);
    }
    public SignalCompositionPipeline(String alias, double pips, SignalComposerConfig config, Consumer<CompositionUpdate> output,
            Consumer<PatternEvent> rawEvents, Consumer<String> diagnostics, LongSupplier receiptClock) {
        this.output = output; composer = new SignalComposer(alias, 1, config, receiptClock);
        observer = new PatternObservationEngine(alias, pips, config, event -> {
            rawEvents.accept(event); output.accept(composer.onPatternEvent(event));
        }, (reason, epoch) -> {
            bid = ask = last = 0; output.accept(composer.reset(reason, epoch));
        }, diagnostics);
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
    public void reset(ResetReason reason) { observer.reset(reason); }
    public long epoch() { return observer.epoch(); }
    public boolean usable() { return observer.usable(); }
}
