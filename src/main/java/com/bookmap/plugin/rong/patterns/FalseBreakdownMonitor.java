package com.bookmap.plugin.rong.patterns;

import java.time.*;
import java.util.*;

/** Independent cleared-bid, ask-breakdown, and long-reclaim tracking. Advisory only. */
public final class FalseBreakdownMonitor {
    public static final String PATTERN = "falseBreakdown";
    public static final int MAX_ENTRIES = 3;
    public static final int SIZE_THRESHOLD = WallPatternMonitor.SIZE_THRESHOLD;
    public static final long BID_MIN_DURATION_NS = WallPatternMonitor.BID_MIN_DURATION_NS;
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    public enum Phase { CLEARED, BREAKDOWN, RECLAIMED }

    public static final class Level {
        public final int bidTick, number;
        public final long clearedTimeNs, breakdownTimeNs, reclaimTimeNs;
        public final Phase phase;
        Level(int tick, int number, long cleared, long breakdown, long reclaim) {
            this.bidTick = tick; this.number = number; clearedTimeNs = cleared;
            breakdownTimeNs = breakdown; reclaimTimeNs = reclaim;
            phase = reclaim > 0 ? Phase.RECLAIMED : breakdown > 0 ? Phase.BREAKDOWN : Phase.CLEARED;
        }
        public String toString() { return number + ":" + bidTick + ":" + clearedTimeNs + ":" + breakdownTimeNs + ":" + reclaimTimeNs; }
    }
    public static final class Snapshot {
        public final boolean enabled, regularSession;
        public final Integer highlightedTick;
        public final int highlightedSize, highlightedNumber;
        public final List<Level> entries;
        Snapshot(boolean enabled, boolean regular, Integer tick, int size, int number, List<Level> levels) {
            this.enabled = enabled; regularSession = regular; highlightedTick = tick;
            highlightedSize = size; highlightedNumber = number; entries = List.copyOf(levels);
        }
    }

    private final TreeMap<Integer, Integer> bids = new TreeMap<>();
    private final TreeMap<Integer, Integer> asks = new TreeMap<>();
    private final Map<Integer, Long> largeSince = new HashMap<>();
    private final Set<Integer> clearedPrices = new HashSet<>();
    private final List<Level> levels = new ArrayList<>();
    private boolean enabled, regularSession;
    private LocalDate sessionDate;
    private long lastTimeNs;
    private Integer currentPrice, activeBid;
    private Integer latestBid, latestAsk;

    public synchronized void setEnabled(boolean value) { enabled = value; selectBid(); }
    public synchronized void reset() {
        bids.clear(); asks.clear(); largeSince.clear(); clearedPrices.clear(); levels.clear();
        lastTimeNs = 0; sessionDate = null; currentPrice = activeBid = latestBid = latestAsk = null; regularSession = false;
    }
    private void advanceTime(long timeNs) {
        if (timeNs <= 0) { regularSession = false; return; }
        if (lastTimeNs > 0 && timeNs < lastTimeNs) reset();
        lastTimeNs = timeNs;
        ZonedDateTime time = Instant.ofEpochSecond(timeNs / 1_000_000_000L, timeNs % 1_000_000_000L).atZone(NEW_YORK);
        if (!time.toLocalDate().equals(sessionDate)) {
            levels.clear(); clearedPrices.clear(); largeSince.clear(); activeBid = currentPrice = null;
            latestBid = latestAsk = null;
            sessionDate = time.toLocalDate();
            bids.forEach((tick, size) -> { if (size > SIZE_THRESHOLD) largeSince.put(tick, timeNs); });
        }
        LocalTime local = time.toLocalTime();
        regularSession = time.getDayOfWeek().getValue() <= 5
                && !local.isBefore(LocalTime.of(9, 30)) && local.isBefore(LocalTime.of(16, 0));
    }
    public synchronized void onTimestamp(long timeNs) { advanceTime(timeNs); selectBid(); }
    public synchronized void onDepth(boolean bid, int price, int size, long timeNs) {
        onDepth(bid, price, size, timeNs, true);
    }
    public synchronized void onDepth(boolean bid, int price, int size, long timeNs, boolean ready) {
        advanceTime(timeNs); selectBid();
        if (!bid) {
            if (size > 0) asks.put(price, size); else asks.remove(price);
            updateDepthQuote(timeNs, ready); selectBid(); return;
        }
        if (size > SIZE_THRESHOLD) largeSince.putIfAbsent(price, timeNs); else largeSince.remove(price);
        if (size > 0) bids.put(price, size); else bids.remove(price);
        // The tracked large wall can be consumed while smaller residual orders remain.
        if (Integer.valueOf(price).equals(activeBid) && size <= SIZE_THRESHOLD) {
            if (enabled && ready && regularSession && levels.size() < MAX_ENTRIES && clearedPrices.add(price))
                levels.add(new Level(price, levels.size() + 1, timeNs, 0, 0));
            activeBid = null;
        }
        updateDepthQuote(timeNs, ready);
        if (enabled && ready && regularSession) evaluateQuote(timeNs);
        selectBid();
    }
    private void updateDepthQuote(long timeNs, boolean ready) {
        // Replay can deliver top-of-book changes through depth without a separate BBO callback.
        if (!bids.isEmpty() && !asks.isEmpty()) onBbo(bids.lastKey(), asks.firstKey(), timeNs, ready);
    }
    public synchronized void onTrade(int price, int size, Boolean buy, long timeNs, boolean ready) {
        advanceTime(timeNs); currentPrice = price; selectBid();
        // Trade prints alone neither clear a bid nor confirm its breakdown.
    }
    public synchronized void onBbo(int bidPrice, int askPrice, long timeNs, boolean ready) {
        advanceTime(timeNs);
        if (bidPrice <= 0 || askPrice <= bidPrice) return;
        currentPrice = bidPrice;
        if (ready && regularSession) {
            latestBid = bidPrice; latestAsk = askPrice;
            if (enabled) evaluateQuote(timeNs);
        }
        selectBid();
    }
    private void evaluateQuote(long timeNs) {
        if (latestBid == null || latestAsk == null) return;
        // Quotes persist until changed. Depth may clear the wall after an ask-below callback.
        for (int i = 0; i < levels.size(); i++) {
                Level level = levels.get(i);
                if (level.phase == Phase.CLEARED && timeNs >= level.clearedTimeNs && latestAsk < level.bidTick)
                    levels.set(i, new Level(level.bidTick, level.number, level.clearedTimeNs, timeNs, 0));
                else if (level.phase == Phase.BREAKDOWN && timeNs >= level.breakdownTimeNs && latestBid > level.bidTick)
                    levels.set(i, new Level(level.bidTick, level.number, level.clearedTimeNs, level.breakdownTimeNs, timeNs));
        }
    }
    private void selectBid() {
        if (!enabled || !regularSession || currentPrice == null || levels.size() >= MAX_ENTRIES) { activeBid = null; return; }
        // Keep the already-qualified level while it is partially filled.
        if (activeBid != null && bids.getOrDefault(activeBid, 0) > 0
                && (bids.getOrDefault(activeBid, 0) <= SIZE_THRESHOLD || activeBid > currentPrice)) return;
        activeBid = null;
        for (Map.Entry<Integer, Integer> bid : bids.headMap(currentPrice, true).descendingMap().entrySet()) {
            if (bid.getValue() > SIZE_THRESHOLD && !clearedPrices.contains(bid.getKey())
                    && lastTimeNs - largeSince.getOrDefault(bid.getKey(), lastTimeNs) > BID_MIN_DURATION_NS) {
                activeBid = bid.getKey(); break;
            }
        }
    }
    public synchronized Snapshot snapshot() {
        Integer highlight = enabled && regularSession ? activeBid : null;
        return new Snapshot(enabled, regularSession, highlight, highlight == null ? 0 : bids.getOrDefault(highlight, 0),
                levels.size() + 1, enabled ? levels : List.of());
    }
}
