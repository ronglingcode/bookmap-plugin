package com.bookmap.plugin.rong.patterns;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Shared wall qualification for independent pattern monitors; never executes orders. */
public abstract class WallPatternMonitor {
    public static final int SIZE_THRESHOLD = 5_000;
    public static final long BID_MIN_DURATION_NS = 1_000_000_000L;
    private static final long DEPTH_TRADE_GRACE_NS = 250_000_000L;
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    public enum Phase { BREAKOUT, BID_BREAKDOWN, COMPLETE }

    public static final class Entry {
        public final int bidTick, priceTick, number;
        public final long timeNs;
        Entry(int bidTick, int priceTick, int number, long timeNs) {
            this.bidTick = bidTick; this.priceTick = priceTick; this.number = number; this.timeNs = timeNs;
        }
    }

    public static final class Snapshot {
        public final boolean enabled, regularSession;
        public final Phase phase;
        public final Integer highlightedTick;
        public final int highlightedSize;
        public final List<Entry> entries;
        Snapshot(boolean enabled, boolean regularSession, Phase phase, Integer tick, int size, List<Entry> entries) {
            this.enabled = enabled; this.regularSession = regularSession; this.phase = phase;
            this.highlightedTick = tick; this.highlightedSize = size; this.entries = List.copyOf(entries);
        }
    }

    private final TreeMap<Integer, Integer> offers = new TreeMap<>(), bids = new TreeMap<>();
    private final Set<Integer> signaledBids = new HashSet<>();
    private final Map<Integer, Long> bidLargeSince = new TreeMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private boolean enabled, regularSession;
    private LocalDate sessionDate;
    private long lastTimeNs;
    private int lastOfferMinimumSize;
    private Integer currentPrice, highlightedTick, armedOffer, armedBid;
    private int highlightedSize, offerSize;
    private long offerExecuted;
    private long armedBidRetainUntilNs;
    private final java.util.function.IntSupplier offerThreshold;
    private int offerMinimumSize() { return offerThreshold.getAsInt(); }
    private int bidMinimumSize() { return SIZE_THRESHOLD; }
    private final Phase initialPhase;
    private final int maxEntries;
    private Phase phase;

    protected WallPatternMonitor(Phase initialPhase, int maxEntries) {
        this(initialPhase, maxEntries, () -> SIZE_THRESHOLD);
    }
    protected WallPatternMonitor(Phase initialPhase, int maxEntries, java.util.function.IntSupplier threshold) {
        offerThreshold = threshold;
        this.initialPhase = initialPhase; this.maxEntries = maxEntries; phase = initialPhase;
    }

    public synchronized void setEnabled(boolean value) {
        enabled = value;
        selectHighlight();
    }

    /** A replay seek discards the old book as well as the old pattern. */
    public synchronized void reset() {
        offers.clear(); bids.clear(); currentPrice = null; sessionDate = null;
        lastTimeNs = 0; regularSession = false; resetSession();
    }

    private void resetSession() {
        phase = initialPhase; entries.clear(); signaledBids.clear();
        bidLargeSince.clear(); armedBidRetainUntilNs = 0;
        highlightedTick = armedOffer = armedBid = null; highlightedSize = offerSize = 0; offerExecuted = 0;
    }

    public synchronized void onTimestamp(long timeNs) {
        advanceTime(timeNs); selectHighlight();
    }

    private void advanceTime(long timeNs) {
        if (timeNs <= 0) { regularSession = false; return; }
        if (lastTimeNs > 0 && timeNs < lastTimeNs) reset();
        lastTimeNs = timeNs;
        int minimum = offerMinimumSize();
        if (minimum != lastOfferMinimumSize) {
            if (armedOffer != null && offers.getOrDefault(armedOffer, 0) <= minimum) { armedOffer = null; offerExecuted = 0; }
            lastOfferMinimumSize = minimum;
        }
        ZonedDateTime time = Instant.ofEpochSecond(timeNs / 1_000_000_000L, timeNs % 1_000_000_000L).atZone(NEW_YORK);
        if (!time.toLocalDate().equals(sessionDate)) {
            // Overnight depth remains useful, but yesterday's last trade does not arm a new target.
            resetSession(); currentPrice = null; sessionDate = time.toLocalDate();
            bids.forEach((tick, size) -> { if (size > bidMinimumSize()) bidLargeSince.put(tick, timeNs); });
        }
        LocalTime local = time.toLocalTime();
        regularSession = time.getDayOfWeek().getValue() <= 5
                && !local.isBefore(LocalTime.of(9, 30)) && local.isBefore(LocalTime.of(16, 0));
        if (armedBid != null && bids.getOrDefault(armedBid, 0) <= bidMinimumSize() && timeNs > armedBidRetainUntilNs) armedBid = null;
    }

    public synchronized void onDepth(boolean bid, int priceTick, int size, long timeNs) {
        advanceTime(timeNs);
        selectHighlight();
        TreeMap<Integer, Integer> book = bid ? bids : offers;
        if (bid) {
            if (size > bidMinimumSize()) bidLargeSince.putIfAbsent(priceTick, timeNs);
            else {
                bidLargeSince.remove(priceTick);
                if (Integer.valueOf(priceTick).equals(armedBid)) {
                    // Briefly preserve callback ordering, not an indefinitely canceled wall.
                    if (book.getOrDefault(priceTick, 0) > bidMinimumSize() || size == 0)
                        armedBidRetainUntilNs = timeNs + DEPTH_TRADE_GRACE_NS;
                }
            }
        }
        if (size > 0) book.put(priceTick, size); else book.remove(priceTick);
        // Retain the armed target until the next trade: feeds can publish depletion before execution.
        selectHighlight();
    }

    public synchronized void onTrade(int priceTick, int size, Boolean buyAggressor, long timeNs, boolean ready) {
        advanceTime(timeNs);
        selectHighlight();
        if (ready && enabled && regularSession && phase == Phase.BID_BREAKDOWN && armedBid != null
                && priceTick == armedBid && !Boolean.TRUE.equals(buyAggressor)) {
            // Actual selling into a mature bid lets partial filling continue for one second.
            armedBidRetainUntilNs = timeNs + BID_MIN_DURATION_NS;
        }
        if (enabled && ready && regularSession && currentPrice != null) {
            if (phase == Phase.BREAKOUT && armedOffer != null && !Boolean.FALSE.equals(buyAggressor)) {
                if (priceTick == armedOffer) offerExecuted += Math.max(0, size);
                // A print beyond an offer with executions at it also proves it was traded through.
                if (offerExecuted >= offerSize || priceTick > armedOffer && offerExecuted > 0) {
                    phase = Phase.BID_BREAKDOWN; armedOffer = null;
                }
            } else if (phase == Phase.BID_BREAKDOWN && armedBid != null
                    && currentPrice >= armedBid && priceTick < armedBid && signaledBids.add(armedBid)) {
                entries.add(new Entry(armedBid, priceTick, entries.size() + 1, timeNs));
                armedBid = null;
                if (entries.size() == maxEntries) phase = Phase.COMPLETE;
            }
        }
        currentPrice = priceTick;
        // Replace an offer that disappeared without any buying execution after this callback.
        if (armedOffer != null && offers.getOrDefault(armedOffer, 0) <= offerMinimumSize() && offerExecuted == 0) armedOffer = null;
        // Partial fills retain a mature bid briefly; canceled walls expire in market time.
        selectHighlight();
    }

    private void selectHighlight() {
        highlightedTick = null; highlightedSize = 0;
        if (!enabled || !regularSession || currentPrice == null || phase == Phase.COMPLETE) return;
        boolean bid = phase == Phase.BID_BREAKDOWN;
        Map<Integer, Integer> eligible = bid ? bids.headMap(currentPrice, true).descendingMap() : offers.tailMap(currentPrice, false);
        for (Map.Entry<Integer, Integer> level : eligible.entrySet()) {
            if (level.getValue() <= (bid ? bidMinimumSize() : offerMinimumSize()) || bid && signaledBids.contains(level.getKey())) continue;
            if (bid && lastTimeNs - bidLargeSince.getOrDefault(level.getKey(), lastTimeNs) <= BID_MIN_DURATION_NS) continue;
            highlightedTick = level.getKey(); highlightedSize = level.getValue(); break;
        }
        if (bid) {
            if (highlightedTick != null && (armedBid == null || highlightedTick > armedBid
                    || bids.getOrDefault(armedBid, 0) > bidMinimumSize())) {
                if (!highlightedTick.equals(armedBid)) armedBidRetainUntilNs = 0;
                armedBid = highlightedTick;
            }
        } else if (highlightedTick != null && (armedOffer == null
                || armedOffer > currentPrice && offers.getOrDefault(armedOffer, 0) > offerMinimumSize())) {
            if (!highlightedTick.equals(armedOffer)) { offerExecuted = 0; offerSize = highlightedSize; }
            armedOffer = highlightedTick;
            // Before the first execution, use the current displayed size of the tracked offer.
            if (offerExecuted == 0) offerSize = highlightedSize;
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(enabled, regularSession, phase, highlightedTick, highlightedSize, enabled ? entries : List.of());
    }
}
