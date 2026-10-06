package com.bookmap.plugin.rong.signal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import com.bookmap.plugin.rong.patterns.Direction;

/** Shared display data. Receipt TTL never advances the independent market-time context. */
public final class TradingSignalStore {
    public static final int MAX_SIGNALS = 20;
    public static final long DISPLAY_TTL_MS = 30_000;
    public static final class Snapshot {
        public final List<TradingSignal> signals;
        public final Map<Direction, DevelopingContext> contexts;
        public final long epoch, marketTimeNs;
        private Snapshot(State state) {
            signals = Collections.unmodifiableList(new ArrayList<>(state.signals.values()));
            contexts = Collections.unmodifiableMap(new EnumMap<>(state.contexts));
            epoch = state.epoch; marketTimeNs = state.marketTimeNs;
        }
    }
    private static final class State {
        long epoch, marketTimeNs;
        final Map<String, TradingSignal> signals = new LinkedHashMap<>();
        final Map<Direction, DevelopingContext> contexts = new EnumMap<>(Direction.class);
        State(long epoch) { this.epoch = epoch; }
    }
    private final Map<String, State> states = new LinkedHashMap<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final LongSupplier receiptClock;
    public TradingSignalStore() { this(System::currentTimeMillis); }
    public TradingSignalStore(LongSupplier receiptClock) { this.receiptClock = receiptClock; }
    public void publish(String alias, long epoch, CompositionUpdate update) {
        boolean changed = false;
        synchronized (this) {
            State state = states.get(alias);
            if (state != null && epoch < state.epoch) return;
            if (state == null || state.epoch != epoch) { state = new State(epoch); states.put(alias, state); changed = true; }
            changed |= prune(state, receiptClock.getAsLong());
            for (TradingSignal signal : update.signals) {
                if (!alias.equals(signal.trigger.instrumentAlias) || signal.trigger.epoch != epoch) throw new IllegalArgumentException("Foreign display signal");
                if (receiptClock.getAsLong() - signal.createdAtMs >= DISPLAY_TTL_MS) continue;
                TradingSignal previous = state.signals.get(signal.id);
                if (previous == null || signal.revision > previous.revision) {
                    if (previous != null && signal.createdAtMs != previous.createdAtMs) throw new IllegalArgumentException("Revision changed receipt TTL");
                    state.signals.put(signal.id, signal); changed = true;
                }
            }
            while (state.signals.size() > MAX_SIGNALS) { state.signals.remove(state.signals.keySet().iterator().next()); changed = true; }
            changed |= !sameContexts(state.contexts, update.contexts);
            state.contexts.clear(); state.contexts.putAll(update.contexts); state.marketTimeNs = update.marketTimeNs;
        }
        if (changed) notifyListeners(alias);
    }
    private static boolean sameContexts(Map<Direction, DevelopingContext> a, Map<Direction, DevelopingContext> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        for (Direction direction : a.keySet()) {
            DevelopingContext x = a.get(direction), y = b.get(direction);
            if (!x.confirmation.id.equals(y.confirmation.id) || x.confirmation.revision != y.confirmation.revision
                    || x.expiresAtNs != y.expiresAtNs || x.requiredTriggerSize != y.requiredTriggerSize) return false;
        }
        return true;
    }
    private static boolean prune(State state, long nowMs) {
        return state.signals.values().removeIf(signal -> nowMs - signal.createdAtMs >= DISPLAY_TTL_MS);
    }
    public synchronized Snapshot snapshot(String alias) {
        State state = states.get(alias);
        if (state == null) state = new State(0);
        prune(state, receiptClock.getAsLong()); return new Snapshot(state);
    }
    public void clear(String alias, long epoch) {
        synchronized (this) {
            State state = states.get(alias); if (state != null && state.epoch > epoch) return;
            states.put(alias, new State(epoch));
        }
        notifyListeners(alias);
    }
    public void removeAlias(String alias) { synchronized (this) { states.remove(alias); } notifyListeners(alias); }
    public void addListener(Consumer<String> listener) { listeners.add(listener); }
    public void removeListener(Consumer<String> listener) { listeners.remove(listener); }
    private void notifyListeners(String alias) { for (Consumer<String> listener : listeners) listener.accept(alias); }
}
