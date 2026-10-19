package com.bookmap.plugin.rong.patterns;

import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Shared advisory bid engine for Bid Breakdown and False Breakout.
 * Callback processing is final; subclasses customize only the starting condition through hooks.
 */
public class BidBreakdownMonitor {
    public static final String PATTERN = "bidBreakdown";
    public static final int MIN_BID_SIZE = 4500, MAX_ENTRIES = 5;
    public static final long BID_MIN_DURATION_NS = 1_000_000_000L;
    public static final long CONFIRMATION_NS = 1_000_000_000L;
    private static final long REMOVAL_GRACE_NS = 250_000_000L;
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private final BooleanSupplier entryLiquidityReady;
    private final TreeMap<Integer, Integer> bids = new TreeMap<>();
    private final Map<Integer, Long> largeSince = new HashMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private boolean enabled, regularSession, ready, liquid;
    private LocalDate date;
    private long lastTimeNs, askBelowSince, removedAt;
    private Integer marketPrice, bestBid, bestAsk, nextBid;

    public static final class Entry {
        public final int number, bidTick;
        public final long entryTimeNs, reclaimTimeNs;
        Entry(int number, int tick, long entryTime, long reclaimTime) {
            this.number = number; bidTick = tick; entryTimeNs = entryTime; reclaimTimeNs = reclaimTime;
        }
        public String toString() { return number + ":" + bidTick + ":" + entryTimeNs + ":" + reclaimTimeNs; }
    }
    public static final class Snapshot {
        public final boolean enabled, regularSession, ready, liquid;
        public final Integer nextBidTick, offerTick;
        public final boolean waitingForOffer;
        public final List<Entry> entries;
        Snapshot(BidBreakdownMonitor monitor) {
            enabled = monitor.enabled; regularSession = monitor.regularSession; ready = monitor.ready; liquid = monitor.liquid;
            nextBidTick = enabled ? monitor.nextBid : null;
            waitingForOffer = monitor.waitingForOffer();
            offerTick = enabled && waitingForOffer ? monitor.offerTick() : null;
            entries = enabled ? List.copyOf(monitor.entries) : List.of();
        }
    }
    public BidBreakdownMonitor(BooleanSupplier entryLiquidityReady) { this.entryLiquidityReady = entryLiquidityReady; }
    public final synchronized void setEnabled(boolean value) {
        if (enabled != value) clearPending();
        enabled = value;
    }
    protected boolean startConditionMet() { return entryLiquidityReady.getAsBoolean(); }
    protected boolean waitingForOffer() { return false; }
    protected Integer offerTick() { return null; }
    protected void resetStart(boolean clearBook) { }
    protected void observeDepth(boolean bid, int price, int size) { }
    protected void observeTrade(int price, int size, Boolean buyAggressor, boolean canObserve) { }
    private void clearPending() { nextBid = null; askBelowSince = removedAt = 0; }
    private void clearSession(boolean clearBook) {
        resetStart(clearBook); largeSince.clear();
        entries.clear(); clearPending(); marketPrice = bestBid = bestAsk = null;
    }
    private void advance(long timeNs, boolean snapshotReady) {
        if (timeNs <= 0) { ready = regularSession = liquid = false; clearPending(); return; }
        boolean seek = lastTimeNs > 0 && timeNs < lastTimeNs;
        if (seek) { bids.clear(); clearSession(true); ready = false; }
        lastTimeNs = timeNs;
        ZonedDateTime time = Instant.ofEpochSecond(timeNs / 1_000_000_000L, timeNs % 1_000_000_000L).atZone(NEW_YORK);
        if (!time.toLocalDate().equals(date)) {
            clearSession(false); date = time.toLocalDate();
            bids.forEach((tick, size) -> { if (size >= MIN_BID_SIZE) largeSince.put(tick, timeNs); });
        }
        LocalTime local = time.toLocalTime();
        regularSession = time.getDayOfWeek().getValue() <= 5 && !local.isBefore(LocalTime.of(9, 30)) && local.isBefore(LocalTime.of(16, 0));
        ready = !seek && snapshotReady;
        refreshStart();
    }
    private void refreshStart() {
        liquid = enabled && regularSession && ready && startConditionMet();
        if (!active()) clearPending();
    }
    private boolean active() { return enabled && regularSession && ready && liquid; }
    private boolean used(int tick) { return entries.stream().anyMatch(entry -> entry.bidTick == tick); }
    private void select() {
        if (!active() || entries.size() >= MAX_ENTRIES) { clearPending(); return; }
        if (nextBid != null) {
            // Retain a broken level while confirmation runs, even after its depth disappears.
            if (askBelowSince != 0) return;
            if (bids.getOrDefault(nextBid, 0) >= MIN_BID_SIZE) {
                // Still locate a newly closer qualifying bid before a breakdown starts.
                clearPending();
            } else if (removedAt != 0 && lastTimeNs - removedAt <= REMOVAL_GRACE_NS) return;
            else clearPending();
        }
        Integer ceiling = bestBid != null ? bestBid : marketPrice;
        if (ceiling == null) return;
        for (Map.Entry<Integer, Integer> bid : bids.headMap(ceiling, true).descendingMap().entrySet()) {
            if (bid.getValue() >= MIN_BID_SIZE && !used(bid.getKey())
                    && lastTimeNs - largeSince.getOrDefault(bid.getKey(), lastTimeNs) > BID_MIN_DURATION_NS) {
                nextBid = bid.getKey(); break;
            }
        }
    }
    private void evaluate() {
        if (!active()) return;
        if (bestBid != null) for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.reclaimTimeNs == 0 && bestBid >= entry.bidTick)
                entries.set(i, new Entry(entry.number, entry.bidTick, entry.entryTimeNs, lastTimeNs));
        }
        if (nextBid != null && askBelowSince != 0 && bestAsk != null && bestAsk < nextBid
                && lastTimeNs - askBelowSince >= CONFIRMATION_NS) {
            entries.add(new Entry(entries.size() + 1, nextBid, lastTimeNs, 0));
            clearPending();
        }
        select();
    }
    public final synchronized void onTimestamp(long timeNs, boolean snapshotReady) {
        advance(timeNs, snapshotReady); evaluate();
    }
    public final synchronized void onDepth(boolean bid, int price, int size, long timeNs, boolean snapshotReady) {
        advance(timeNs, snapshotReady);
        observeDepth(bid, price, size);
        if (bid && price > 0) {
            if (size >= MIN_BID_SIZE) largeSince.putIfAbsent(price, timeNs); else largeSince.remove(price);
            if (size > 0) bids.put(price, size); else bids.remove(price);
            if (Integer.valueOf(price).equals(nextBid) && size < MIN_BID_SIZE && removedAt == 0) removedAt = timeNs;
        }
        refreshStart(); evaluate();
    }
    public final synchronized void onTrade(int price, int size, long timeNs, boolean snapshotReady) {
        onTrade(price, size, null, timeNs, snapshotReady);
    }
    public final synchronized void onTrade(int price, int size, Boolean buyAggressor, long timeNs, boolean snapshotReady) {
        advance(timeNs, snapshotReady);
        if (price <= 0 || size <= 0) return;
        observeTrade(price, size, buyAggressor, enabled && regularSession && ready);
        marketPrice = price;
        refreshStart(); evaluate();
    }
    public final synchronized void onBbo(int bid, int ask, long timeNs, boolean snapshotReady) {
        advance(timeNs, snapshotReady);
        if (bid <= 0 || ask <= bid) { bestBid = bestAsk = null; askBelowSince = 0; return; }
        select(); // A bid may have matured since the last callback, before this quote crosses it.
        // Preserve the highlighted level across a quote crossing.
        if (active() && nextBid != null) {
            if (ask < nextBid) { if (askBelowSince == 0) askBelowSince = timeNs; }
            else askBelowSince = 0;
        }
        bestBid = bid; bestAsk = ask; marketPrice = bid;
        evaluate();
    }
    public final synchronized Snapshot snapshot() { return new Snapshot(this); }
}
