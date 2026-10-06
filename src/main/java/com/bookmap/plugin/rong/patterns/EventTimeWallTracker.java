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
        public final List<String> diagnostics;
        private Update(List<Wall> walls, List<String> diagnostics) {
            qualified = Collections.unmodifiableList(new ArrayList<>(walls));
            this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        }
    }
    private static final class Phase {
        final String id; final boolean bid; final int price; final long firstSeen;
        long size; boolean qualified;
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
            phase.size = size;
            if (!phase.qualified && size < config.observationFloorSize) phases.remove(key);
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
        for (Phase phase : phases.values()) if (!phase.qualified && phase.size >= config.observationFloorSize
                && now - phase.firstSeen >= config.detectors.wallLifetimeMs * 1_000_000L) {
            phase.qualified = true; qualified.add(new Wall(phase));
        }
        return new Update(qualified, diagnostics);
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
