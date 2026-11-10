package com.bookmap.plugin.rong.patterns;

import java.util.function.Consumer;
import com.bookmap.plugin.rong.signal.CairoPatternSnapshot;
import com.bookmap.plugin.rong.signal.ResetReason;
import com.google.gson.JsonObject;

/** Serialized callback-to-pattern-event path. No composition or execution decisions. */
public final class PatternEventPipeline {
    private final PatternObservationConfig rules;
    private final CairoPatternSnapshot aggregate;
    private final PatternObservationEngine observer;
    private Consumer<ResetReason> resetListener = reason -> {};
    private int bid, ask;

    public PatternEventPipeline(String alias, double pips, PatternObservationConfig config,
            Consumer<PatternEvent> events, Consumer<String> diagnostics) {
        rules = config;
        aggregate = new CairoPatternSnapshot(alias, pips, "unknown", config.revision);
        observer = new PatternObservationEngine(alias, pips, config, event -> {
            aggregate.event(event); events.accept(event);
        }, (reason, epoch) -> {
            bid = ask = 0; aggregate.reset(epoch); resetListener.accept(reason);
        }, diagnostics);
    }
    public void onDepth(boolean bid, int price, long size, long timeNs, PatternEvent.TimestampProvenance provenance) {
        observer.onDepth(bid, price, size, timeNs, provenance);
    }
    public void onTrade(int price, long size, Boolean buy, long timeNs, PatternEvent.TimestampProvenance provenance) {
        observer.onTrade(price, size, buy, timeNs, provenance);
    }
    public void onBbo(int bid, int ask, long timeNs, PatternEvent.TimestampProvenance provenance) {
        observer.onBbo(bid, ask, timeNs, provenance);
        if (observer.usable() && provenance == PatternEvent.TimestampProvenance.MARKET && !(bid > 0 && ask > 0 && bid > ask)) {
            this.bid = bid; this.ask = ask;
        }
    }
    public void onTimestamp(long timeNs, PatternEvent.TimestampProvenance provenance) { observer.onTimestamp(timeNs, provenance); }
    public JsonObject aggregateSnapshot(JsonObject liquidity, boolean reconnect) {
        JsonObject value = aggregate.snapshot(observer.usable(), observer.nowNs(), liquidity, reconnect);
        value.add("rules", rules.toJson());
        return value;
    }
    public int bestBidTick() { return bid; }
    public int bestAskTick() { return ask; }
    public void markReady() { observer.markReady(); }
    public void setResetListener(Consumer<ResetReason> listener) { resetListener = listener; }
    public void reset(ResetReason reason) { observer.reset(reason); }
    public long epoch() { return observer.epoch(); }
    public boolean usable() { return observer.usable(); }
}
