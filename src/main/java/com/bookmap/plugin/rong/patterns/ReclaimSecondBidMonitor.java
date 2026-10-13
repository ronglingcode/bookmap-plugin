package com.bookmap.plugin.rong.patterns;

import java.time.*;
import java.util.*;

/** Rolling two filled bid levels and advisory Level 1 bid reclaim signals. */
public final class ReclaimSecondBidMonitor {
    public static final String PATTERN = "reclaim2ndBid";
    public static final int SIZE_THRESHOLD = WallPatternMonitor.SIZE_THRESHOLD;
    public static final long BID_MIN_DURATION_NS = WallPatternMonitor.BID_MIN_DURATION_NS;
    private static final long FILL_CALLBACK_GRACE_NS = 250_000_000L;
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    public static final class Fill {
        public final int bidTick, qualifiedSize;
        public final long firstFillTimeNs, filledSize;
        Fill(int tick, int qualifiedSize, long timeNs, long filledSize) {
            bidTick = tick; this.qualifiedSize = qualifiedSize; firstFillTimeNs = timeNs; this.filledSize = filledSize;
        }
        public String toString() { return bidTick + ":" + firstFillTimeNs + ":" + filledSize; }
    }
    public static final class LongSignal {
        public final int priceTick, olderBidTick, newerBidTick;
        public final long timeNs;
        LongSignal(int price, int older, int newer, long time) {
            priceTick = price; olderBidTick = older; newerBidTick = newer; timeNs = time;
        }
        public String toString() { return priceTick + ":" + timeNs; }
    }
    public static final class Snapshot {
        public final boolean enabled, regularSession;
        public final Integer nextBidTick;
        public final List<Fill> fills;
        public final List<LongSignal> signals;
        Snapshot(boolean enabled, boolean regular, Integer next, List<Fill> fills, List<LongSignal> signals) {
            this.enabled = enabled; regularSession = regular; nextBidTick = next;
            this.fills = List.copyOf(fills); this.signals = List.copyOf(signals);
        }
    }
    private static final class ReducedBid {
        final int qualifiedSize;
        final long expiresAtNs;
        ReducedBid(int size, long expires) { qualifiedSize = size; expiresAtNs = expires; }
    }
    private final TreeMap<Integer, Integer> bids = new TreeMap<>(), asks = new TreeMap<>();
    private final Map<Integer, Long> largeSince = new HashMap<>();
    private final Map<Integer, ReducedBid> recentlyReduced = new HashMap<>();
    private final List<Fill> fills = new ArrayList<>();
    private final List<LongSignal> signals = new ArrayList<>();
    private boolean enabled, regularSession, pairSignaled;
    private LocalDate sessionDate;
    private long lastTimeNs;
    private Integer currentPrice, nextBid, latestBid;

    public synchronized void setEnabled(boolean value) { enabled = value; selectNextBid(); }
    public synchronized void reset() {
        bids.clear(); asks.clear(); resetSession(); sessionDate = null; lastTimeNs = 0; regularSession = false;
    }
    private void resetSession() {
        largeSince.clear(); recentlyReduced.clear(); fills.clear(); signals.clear();
        pairSignaled = false; currentPrice = nextBid = latestBid = null;
    }
    private void advanceTime(long timeNs) {
        if (timeNs <= 0) { regularSession = false; return; }
        if (lastTimeNs > 0 && timeNs < lastTimeNs) reset();
        lastTimeNs = timeNs;
        ZonedDateTime time = Instant.ofEpochSecond(timeNs / 1_000_000_000L, timeNs % 1_000_000_000L).atZone(NEW_YORK);
        if (!time.toLocalDate().equals(sessionDate)) {
            resetSession(); sessionDate = time.toLocalDate();
            bids.forEach((tick, size) -> { if (size > SIZE_THRESHOLD) largeSince.put(tick, timeNs); });
        }
        LocalTime local = time.toLocalTime();
        regularSession = time.getDayOfWeek().getValue() <= 5
                && !local.isBefore(LocalTime.of(9, 30)) && local.isBefore(LocalTime.of(16, 0));
        recentlyReduced.entrySet().removeIf(entry -> timeNs > entry.getValue().expiresAtNs);
    }
    public synchronized void onTimestamp(long timeNs) { advanceTime(timeNs); selectNextBid(); }
    private int qualifiedSize(int tick, long timeNs) {
        if (bids.getOrDefault(tick, 0) > SIZE_THRESHOLD
                && timeNs - largeSince.getOrDefault(tick, timeNs) > BID_MIN_DURATION_NS) return bids.get(tick);
        ReducedBid reduced = recentlyReduced.get(tick);
        return reduced != null && timeNs <= reduced.expiresAtNs ? reduced.qualifiedSize : 0;
    }
    public synchronized void onDepth(boolean bid, int price, int size, long timeNs, boolean ready) {
        advanceTime(timeNs);
        if (bid) {
            if (size <= SIZE_THRESHOLD) {
                int qualified = qualifiedSize(price, timeNs);
                if (bids.getOrDefault(price, 0) > SIZE_THRESHOLD && qualified > 0)
                    recentlyReduced.put(price, new ReducedBid(qualified, timeNs + FILL_CALLBACK_GRACE_NS));
                largeSince.remove(price);
            } else {
                largeSince.putIfAbsent(price, timeNs); recentlyReduced.remove(price);
            }
        }
        TreeMap<Integer, Integer> book = bid ? bids : asks;
        if (size > 0) book.put(price, size); else book.remove(price);
        if (!bids.isEmpty() && !asks.isEmpty()) onBbo(bids.lastKey(), asks.firstKey(), timeNs, ready);
        selectNextBid(); // Depth size reductions alone are never treated as fills.
    }
    public synchronized void onTrade(int price, int size, Boolean buyAggressor, long timeNs, boolean ready) {
        advanceTime(timeNs); selectNextBid();
        boolean selling = Boolean.FALSE.equals(buyAggressor)
                || buyAggressor == null && Integer.valueOf(price).equals(latestBid);
        if (enabled && ready && regularSession && selling && size > 0) {
            int index = -1;
            for (int i = 0; i < fills.size(); i++) if (fills.get(i).bidTick == price) index = i;
            if (index >= 0) {
                Fill fill = fills.get(index);
                fills.set(index, new Fill(price, fill.qualifiedSize, fill.firstFillTimeNs, fill.filledSize + size));
            } else {
                int qualified = qualifiedSize(price, timeNs);
                if (qualified > 0) {
                    if (fills.size() == 2) fills.remove(0);
                    fills.add(new Fill(price, qualified, timeNs, size)); pairSignaled = false;
                }
            }
        }
        currentPrice = price; selectNextBid();
    }
    public synchronized void onBbo(int bidPrice, int askPrice, long timeNs, boolean ready) {
        advanceTime(timeNs);
        if (bidPrice <= 0 || askPrice <= bidPrice) return;
        currentPrice = bidPrice;
        if (ready && regularSession) {
            latestBid = bidPrice;
            if (enabled && fills.size() == 2 && !pairSignaled
                    && bidPrice > fills.get(0).bidTick && bidPrice > fills.get(1).bidTick) {
                signals.add(new LongSignal(bidPrice, fills.get(0).bidTick, fills.get(1).bidTick, timeNs));
                if (signals.size() > 64) signals.remove(0);
                pairSignaled = true;
            }
        }
        selectNextBid();
    }
    private void selectNextBid() {
        nextBid = null;
        if (!enabled || !regularSession || currentPrice == null) return;
        for (Map.Entry<Integer, Integer> bid : bids.headMap(currentPrice, true).descendingMap().entrySet()) {
            if (bid.getValue() <= SIZE_THRESHOLD || qualifiedSize(bid.getKey(), lastTimeNs) == 0) continue;
            boolean filled = false;
            for (Fill fill : fills) if (fill.bidTick == bid.getKey()) filled = true;
            if (!filled) { nextBid = bid.getKey(); break; }
        }
    }
    public synchronized Snapshot snapshot() {
        return new Snapshot(enabled, regularSession, nextBid, enabled ? fills : List.of(), enabled ? signals : List.of());
    }
}
