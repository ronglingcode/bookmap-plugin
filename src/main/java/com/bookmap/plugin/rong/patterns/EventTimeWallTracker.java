package com.bookmap.plugin.rong.patterns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.bookmap.plugin.rong.signal.SignalComposerConfig;

/** Independent absolute-depth lifecycle; never reads a legacy percentile threshold. */
public final class EventTimeWallTracker {
    public static final class Wall {
        public final String phaseId;
        public final boolean bid, qualified;
        public final int priceTick;
        public final long size, firstSeenNs;
        private Wall(Phase phase) {
            phaseId = phase.id; bid = phase.bid; qualified = phase.qualified;
            priceTick = phase.price; size = phase.size; firstSeenNs = phase.firstSeen;
        }
    }
    public static final class Update {
        public final List<Wall> qualified;
        public final List<Clear> clears;
        public final List<String> diagnostics;
        private Update(List<Wall> walls, List<Clear> clears, List<String> diagnostics) {
            qualified = Collections.unmodifiableList(new ArrayList<>(walls));
            this.clears = Collections.unmodifiableList(new ArrayList<>(clears));
            this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        }
    }
    /** A measured stable loss. Attribution is intentionally not inferred by this tracker. */
    public static final class Clear {
        public final String phaseId;
        public final boolean bid;
        public final int priceTick;
        public final long previousSize, remainingSize, removedSize, occurrenceNs, observedAtNs;
        private Clear(Phase phase, long observedNs) {
            phaseId = phase.id; bid = phase.bid; priceTick = phase.price;
            previousSize = phase.preClearSize; remainingSize = phase.size;
            removedSize = Math.max(0, previousSize - remainingSize);
            occurrenceNs = phase.pendingClearNs; observedAtNs = observedNs;
        }
    }
    private static final class Phase {
        final String id; final boolean bid; final int price; final long firstSeen;
        long size, pendingClearNs, preClearSize; boolean qualified;
        Phase(String id, boolean bid, int price, long size, long time) {
            this.id = id; this.bid = bid; this.price = price; this.size = size; firstSeen = time;
        }
    }
    private final SignalComposerConfig config;
    private final Map<String, Phase> phases = new LinkedHashMap<>();
    private long epoch, sequence, now;
    public EventTimeWallTracker(long epoch, SignalComposerConfig config) {
        if (epoch <= 0 || !config.valid) throw new IllegalArgumentException("Invalid wall context");
        this.epoch = epoch; this.config = config;
    }
    private static String key(boolean bid, int price) { return (bid ? "B:" : "O:") + price; }
    public Update onDepth(boolean bid, int price, long size, long timeNs) {
        checkTime(timeNs);
        if (price <= 0 || size < 0) throw new IllegalArgumentException("Invalid absolute depth");
        List<String> diagnostics = new ArrayList<>();
        String key = key(bid, price); Phase phase = phases.get(key);
        if (phase == null && size >= config.observationFloorSize) {
            phase = new Phase(epoch + ":wall:" + ++sequence, bid, price, size, timeNs); phases.put(key, phase);
        } else if (phase != null) {
            long previous = phase.size;
            phase.size = size;
            if (!phase.qualified && size < config.observationFloorSize) phases.remove(key);
            if (phase.qualified && phase.pendingClearNs > 0
                    && size > phase.preClearSize * config.detectors.clearRemainingRatio) {
                diagnostics.add("Wall reloaded before stable clear: " + phase.id);
                phase.pendingClearNs = 0; phase.preClearSize = 0;
            }
            if (phase.qualified && phase.pendingClearNs == 0 && previous >= config.observationFloorSize
                    && size <= previous * config.detectors.clearRemainingRatio) {
                phase.pendingClearNs = timeNs; phase.preClearSize = previous;
            }
        }
        while (phases.size() > config.maxWallPhases) {
            String oldest = phases.keySet().iterator().next();
            diagnostics.add("Wall phase capacity eviction: " + phases.remove(oldest).id);
        }
        return advance(diagnostics);
    }
    public Update onTime(long timeNs) { checkTime(timeNs); return advance(Collections.emptyList()); }
    private void checkTime(long timeNs) {
        if (timeNs <= 0 || timeNs < now) throw new IllegalArgumentException("Reset tracker before backwards time");
        now = timeNs;
    }
    private Update advance(List<String> diagnostics) {
        List<Wall> qualified = new ArrayList<>();
        List<Clear> clears = new ArrayList<>();
        for (Phase phase : phases.values()) if (!phase.qualified && phase.size >= config.observationFloorSize
                && now - phase.firstSeen >= config.detectors.wallLifetimeMs * 1_000_000L) {
            phase.qualified = true; qualified.add(new Wall(phase));
        }
        java.util.Iterator<Phase> iterator = phases.values().iterator();
        while (iterator.hasNext()) {
            Phase phase = iterator.next();
            if (phase.pendingClearNs > 0 && now - phase.pendingClearNs >= config.detectors.clearDecisionMs * 1_000_000L) {
                clears.add(new Clear(phase, now)); iterator.remove();
            }
        }
        return new Update(qualified, clears, diagnostics);
    }
    /** Seed each snapshot level at snapshot receipt time; qualification still needs persistence. */
    public Update seed(boolean bid, int price, long size, long timeNs) { return onDepth(bid, price, size, timeNs); }
    public Wall active(boolean bid, int price) {
        Phase phase = phases.get(key(bid, price)); return phase == null ? null : new Wall(phase);
    }
    public List<Wall> snapshot() {
        List<Wall> copy = new ArrayList<>(); for (Phase phase : phases.values()) copy.add(new Wall(phase));
        return Collections.unmodifiableList(copy);
    }
    public void reset(long nextEpoch) {
        if (nextEpoch <= epoch) throw new IllegalArgumentException("New epoch required");
        phases.clear(); epoch = nextEpoch; sequence = 0; now = 0;
    }
}
