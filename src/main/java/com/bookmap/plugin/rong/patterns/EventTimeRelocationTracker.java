package com.bookmap.plugin.rong.patterns;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import com.bookmap.plugin.rong.signal.SignalComposerConfig;

/** Same-side replacement is evidence of a probable move, not proof of ownership. */
public final class EventTimeRelocationTracker {
    public enum Status { NO_MATCH, PROBABLE_MOVE, COVERAGE_GAP }
    private static final class Increase {
        final boolean bid; final int price; final long size, time;
        Increase(boolean bid, int price, long size, long time) {
            this.bid = bid; this.price = price; this.size = size; this.time = time;
        }
    }
    private final SignalComposerConfig config;
    private final Deque<Increase> increases = new ArrayDeque<>();
    private long now, lostThroughNs;
    private boolean overflowed;
    public EventTimeRelocationTracker(SignalComposerConfig config) { this.config = config; }
    public void onDepth(boolean bid, int price, long previousSize, long currentSize, long timeNs) {
        onTime(timeNs);
        if (price <= 0 || previousSize < 0 || currentSize < 0) throw new IllegalArgumentException("Invalid depth increase");
        if (currentSize > previousSize) increases.addLast(new Increase(bid, price, currentSize - previousSize, timeNs));
        while (increases.size() > config.maxWallPhases) {
            overflowed = true; lostThroughNs = Math.max(lostThroughNs, increases.removeFirst().time);
        }
    }
    public void onTime(long timeNs) {
        if (timeNs <= 0 || timeNs < now) throw new IllegalArgumentException("Reset relocation before backwards time");
        now = timeNs;
        long retention = (config.detectors.movePairWindowMs + config.detectors.clearDecisionMs) * 1_000_000L;
        while (!increases.isEmpty() && increases.peekFirst().time < now - retention) increases.removeFirst();
    }
    public Status match(EventTimeWallTracker.Clear clear) {
        long window = config.detectors.movePairWindowMs * 1_000_000L;
        Iterator<Increase> iterator = increases.iterator();
        while (iterator.hasNext()) {
            Increase increase = iterator.next();
            if (increase.bid != clear.bid || increase.price == clear.priceTick
                    || Math.abs(increase.time - clear.occurrenceNs) > window) continue;
            if (Math.abs((double)increase.size - clear.removedSize) <= clear.removedSize * config.detectors.moveSizeToleranceRatio) {
                iterator.remove(); return Status.PROBABLE_MOVE;
            }
        }
        return lostThroughNs > 0 && lostThroughNs >= clear.occurrenceNs - window ? Status.COVERAGE_GAP : Status.NO_MATCH;
    }
    public int size() { return increases.size(); }
    public boolean consumeOverflow() { boolean result = overflowed; overflowed = false; return result; }
    public void reset() { increases.clear(); lostThroughNs = 0; now = 0; overflowed = false; }
}
