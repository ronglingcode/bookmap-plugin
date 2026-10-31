package com.bookmap.plugin.rong;

import java.time.*;
import java.util.*;

/** Single-wall and same-side rolling execution evidence since regular-session open. */
final class SessionOrderSizeTracker {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final long REMOVAL_GRACE_NS = 250_000_000L;
    static final long ROLLING_WINDOW_NS = 2_000_000_000L;
    private final TreeMap<Double, Wall> bids = new TreeMap<>(), offers = new TreeMap<>();
    private final Deque<Fill> rollingFills = new ArrayDeque<>();
    private final Map<Integer, RollingState> rollingStates = new LinkedHashMap<>();
    private LocalDate date;
    private long lastTimestampNs;
    private boolean regular, started, seedFromSharedBook = true;
    private Double bestBid, bestAsk;
    private Observation largest;

    SessionOrderSizeTracker() {
        // Retain a separate peak for every premarket tier. History may arrive before
        // the final premarket requirement; a fallback-tier peak must not qualify 40K.
        rollingStates.put(2000, new RollingState(2000));
        for (int required = 5000; required <= 40000; required += 5000)
            rollingStates.put(required, new RollingState(required));
    }

    private static final class Wall {
        final boolean isBid;
        int size;
        long filled, removedAt, rollingFilled;
        Wall(int size, boolean isBid) { this.size = size; this.isBid = isBid; }
    }
    private static final class Fill {
        final Wall wall;
        final int size;
        final long timestampNs;
        Fill(Wall wall, int size, long timestampNs) {
            this.wall = wall; this.size = size; this.timestampNs = timestampNs;
        }
    }
    private static final class RollingState {
        final long minimum;
        long bidSize, offerSize;
        int bidCount, offerCount;
        RollingObservation best;
        RollingState(int required) { minimum = (required + 3L) / 4; }
        void change(boolean bid, long before, long after) {
            long delta = (after >= minimum ? after : 0) - (before >= minimum ? before : 0);
            int countDelta = (after >= minimum ? 1 : 0) - (before >= minimum ? 1 : 0);
            if (bid) { bidSize += delta; bidCount += countDelta; }
            else { offerSize += delta; offerCount += countDelta; }
        }
        void retainPeak(long timestampNs) {
            boolean bid = bidSize >= offerSize;
            long size = bid ? bidSize : offerSize;
            if (size > 0 && (best == null || size > best.size))
                best = new RollingObservation(size, bid, timestampNs, bid ? bidCount : offerCount);
        }
        void clear() { bidSize = offerSize = 0; bidCount = offerCount = 0; best = null; }
    }
    private void clearRolling() {
        rollingFills.clear(); rollingStates.values().forEach(RollingState::clear);
        for (TreeMap<Double, Wall> side : List.of(bids, offers))
            side.values().forEach(wall -> wall.rollingFilled = 0);
    }
    private void changeRolling(Wall wall, long delta) {
        long before = wall.rollingFilled;
        wall.rollingFilled += delta;
        for (RollingState state : rollingStates.values()) state.change(wall.isBid, before, wall.rollingFilled);
    }
    private void recordRolling(Wall wall, int size, long timestampNs) {
        rollingFills.addLast(new Fill(wall, size, timestampNs));
        changeRolling(wall, size);
        for (RollingState state : rollingStates.values()) state.retainPeak(timestampNs);
    }
    synchronized boolean advance(long timestampNs, OrderBookState book, double pips) {
        if (timestampNs <= 0) { regular = false; return false; }
        ZonedDateTime time = Instant.ofEpochSecond(timestampNs / 1_000_000_000L,
                timestampNs % 1_000_000_000L).atZone(NEW_YORK);
        if (lastTimestampNs > 0 && timestampNs < lastTimestampNs) {
            clearRolling();
            bids.clear(); offers.clear(); largest = null; bestBid = bestAsk = null;
            started = false; seedFromSharedBook = false;
        }
        lastTimestampNs = timestampNs;
        if (!time.toLocalDate().equals(date)) {
            clearRolling();
            date = time.toLocalDate(); largest = null; started = false; bestBid = bestAsk = null;
            // Resting quotes may persist overnight, but yesterday's executions cannot.
            for (TreeMap<Double, Wall> side : List.of(bids, offers)) {
                side.values().removeIf(wall -> wall.removedAt != 0);
                side.values().forEach(wall -> wall.filled = 0);
            }
        }
        LocalTime local = time.toLocalTime();
        regular = time.getDayOfWeek().getValue() <= 5 && !local.isBefore(LocalTime.of(9, 30))
                && local.isBefore(LocalTime.of(16, 0));
        while (!rollingFills.isEmpty() && timestampNs - rollingFills.peekFirst().timestampNs > ROLLING_WINDOW_NS) {
            Fill expired = rollingFills.removeFirst(); changeRolling(expired.wall, -expired.size);
        }
        for (TreeMap<Double, Wall> side : List.of(bids, offers))
            side.values().removeIf(wall -> wall.removedAt != 0 && timestampNs - wall.removedAt > REMOVAL_GRACE_NS);
        if (!regular) return false;
        if (!started) {
            started = true;
            if (seedFromSharedBook) {
                bids.clear(); offers.clear();
                for (boolean bid : new boolean[]{true, false})
                    for (Map.Entry<Integer, Integer> level : book.getLevelsSnapshot(bid).entrySet())
                        if (level.getKey() > 0 && level.getValue() > 0)
                            (bid ? bids : offers).put(BookmapPriceNormalizer.toWirePrice(level.getKey(), pips), new Wall(level.getValue(), bid));
            }
        }
        return true;
    }
    synchronized void onDepth(boolean isBid, double price, int size, long timestampNs, OrderBookState book, double pips) {
        if (timestampNs <= 0) return;
        advance(timestampNs, book, pips);
        if (!Double.isFinite(price) || price <= 0 || size < 0) return;
        TreeMap<Double, Wall> side = isBid ? bids : offers;
        Wall wall = side.get(price);
        if (size == 0) {
            if (wall != null && wall.removedAt == 0) { wall.size = 0; wall.removedAt = timestampNs; }
        } else if (wall == null || wall.removedAt != 0) side.put(price, new Wall(size, isBid));
        else wall.size = size;
    }
    synchronized void onBbo(double bid, double ask, long timestampNs, OrderBookState book, double pips) {
        advance(timestampNs, book, pips);
        bestBid = bestAsk = null;
        if (Double.isFinite(bid) && Double.isFinite(ask) && bid > 0 && ask > bid) { bestBid = bid; bestAsk = ask; }
    }
    synchronized void onTrade(double price, int size, Boolean buyAggressor, long timestampNs,
            boolean ready, OrderBookState book, double pips) {
        recordTrade(price, size, buyAggressor, timestampNs, ready, book, pips, false);
    }
    private void recordTrade(double price, int size, Boolean buyAggressor, long timestampNs,
            boolean ready, OrderBookState book, double pips, boolean historical) {
        if (!advance(timestampNs, book, pips) || !ready || !Double.isFinite(price) || price <= 0 || size <= 0) return;
        Boolean isBid = buyAggressor == null ? null : !buyAggressor;
        if (isBid == null && bestBid != null && bestAsk != null) {
            if (Double.compare(price, bestBid) == 0) isBid = true;
            else if (Double.compare(price, bestAsk) == 0) isBid = false;
        }
        if (isBid == null) return; // Unknown-side prints cannot prove execution against either wall.
        Wall wall = (isBid ? bids : offers).get(price);
        boolean historicalPrint = wall == null;
        if (historicalPrint) {
            if (!historical || buyAggressor == null) return;
            // Without depth, each known-side historical print is its own contributor.
            wall = new Wall(0, isBid);
        }
        wall.filled = Long.MAX_VALUE - wall.filled < size ? Long.MAX_VALUE : wall.filled + size;
        if (largest == null || wall.filled > largest.size)
            largest = new Observation(price, wall.filled, isBid, timestampNs, historicalPrint);
        recordRolling(wall, size, timestampNs);
    }
    synchronized void onHistoricalTrade(double price, int size, Boolean buyAggressor, long timestampNs,
            OrderBookState book, double pips) {
        // One accounting path prevents a print with matching depth from being added twice.
        recordTrade(price, size, buyAggressor, timestampNs, true, book, pips, true);
    }
    synchronized Observation largestObservation() { return largest; }
    synchronized RollingObservation rollingObservation(int required) {
        RollingState state = rollingStates.get(required);
        return state == null ? null : state.best;
    }
    synchronized boolean available() { return regular; }
    synchronized String sessionDate() { return date == null ? "" : date.toString(); }

    static final class RollingObservation {
        final long size, timestampNs;
        final boolean isBid;
        final int contributors;
        RollingObservation(long size, boolean isBid, long timestampNs, int contributors) {
            this.size = size; this.isBid = isBid; this.timestampNs = timestampNs; this.contributors = contributors;
        }
    }

    static final class Observation {
        final double price;
        final long size;
        final boolean isBid;
        final long timestampNs;
        final boolean historicalPrint;
        Observation(double price, long size, boolean isBid, long timestampNs) {
            this(price, size, isBid, timestampNs, false);
        }
        Observation(double price, long size, boolean isBid, long timestampNs, boolean historicalPrint) {
            this.price = price; this.size = size; this.isBid = isBid; this.timestampNs = timestampNs;
            this.historicalPrint = historicalPrint;
        }
    }
}
