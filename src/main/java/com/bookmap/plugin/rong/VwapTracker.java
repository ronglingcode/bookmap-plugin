package com.bookmap.plugin.rong;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

public final class VwapTracker {

    private static final long NS_PER_MS = 1_000_000L;
    private static final long MS_PER_MINUTE = 60_000L;

    public static final class VwapPoint {
        private final long timestampNs;
        private final double value;

        private VwapPoint(long timestampNs, double value) {
            this.timestampNs = timestampNs;
            this.value = value;
        }

        public long getTimestampNs() {
            return timestampNs;
        }

        public double getValue() {
            return value;
        }
    }

    public static final class Snapshot {
        private final LocalDate sessionDate;
        private final double cumulativeVolume;
        private final double cumulativeNotional;

        private Snapshot(
                LocalDate sessionDate,
                double cumulativeVolume,
                double cumulativeNotional) {
            this.sessionDate = sessionDate;
            this.cumulativeVolume = cumulativeVolume;
            this.cumulativeNotional = cumulativeNotional;
        }

        public LocalDate getSessionDate() {
            return sessionDate;
        }

        public double getCumulativeVolume() {
            return cumulativeVolume;
        }

        public double getCumulativeNotional() {
            return cumulativeNotional;
        }

        public double getVwap() {
            return cumulativeNotional / cumulativeVolume;
        }
    }

    private static final class TradeAggregate {
        private double volume;
        private double notional;
        private long lastTimestampNs;

        private void add(double price, int size, long timestampNs) {
            volume += size;
            notional += price * size;
            lastTimestampNs = Math.max(lastTimestampNs, timestampNs);
        }
    }

    private final String symbol;
    private final Map<LocalDate, NavigableMap<Long, TradeAggregate>> bufferedTrades =
            new HashMap<>();
    private final List<VwapPoint> pendingIndicatorPoints = new ArrayList<>();

    private VwapSeedDefinition activeSeed;
    private double cumulativeVolume;
    private double cumulativeNotional;

    public VwapTracker(String symbol) {
        this.symbol = SymbolUtils.cleanSymbol(symbol);
    }

    public synchronized boolean applySeed(VwapSeedDefinition seed) {
        if (seed == null || !symbol.equals(seed.getSymbol())) {
            return false;
        }
        if (activeSeed != null) {
            if (seed.getSessionDate().isBefore(activeSeed.getSessionDate())
                    || seed.getSessionDate().equals(activeSeed.getSessionDate())) {
                return false;
            }
        }

        activeSeed = seed;
        cumulativeVolume = seed.getCumulativeVolume();
        cumulativeNotional = seed.getCumulativeNotional();
        pendingIndicatorPoints.clear();
        pendingIndicatorPoints.add(new VwapPoint(
                seed.getContinueFromTimeMs() * NS_PER_MS,
                currentVwap()));

        NavigableMap<Long, TradeAggregate> sessionTrades =
                bufferedTrades.remove(seed.getSessionDate());
        if (sessionTrades != null) {
            for (TradeAggregate aggregate
                    : sessionTrades.tailMap(seed.getContinueFromTimeMs(), true).values()) {
                cumulativeVolume += aggregate.volume;
                cumulativeNotional += aggregate.notional;
                pendingIndicatorPoints.add(new VwapPoint(
                        aggregate.lastTimestampNs,
                        currentVwap()));
            }
        }
        bufferedTrades.keySet().removeIf(date -> !date.isAfter(seed.getSessionDate()));
        return true;
    }

    public synchronized VwapPoint onTrade(double realPrice, int size, long timestampNs) {
        if (!Double.isFinite(realPrice) || realPrice <= 0 || size <= 0 || timestampNs <= 0) {
            return null;
        }

        long timestampMs = timestampNs / NS_PER_MS;
        LocalDate eventDate = Instant.ofEpochMilli(timestampMs)
                .atZone(VwapSeedDefinition.NEW_YORK_TIME)
                .toLocalDate();
        if (activeSeed != null
                && activeSeed.getSessionDate().equals(eventDate)
                && timestampMs >= activeSeed.getContinueFromTimeMs()) {
            cumulativeVolume += size;
            cumulativeNotional += realPrice * size;
            return new VwapPoint(timestampNs, currentVwap());
        }

        long minuteStartMs = Math.floorDiv(timestampMs, MS_PER_MINUTE) * MS_PER_MINUTE;
        bufferedTrades
                .computeIfAbsent(eventDate, ignored -> new TreeMap<>())
                .computeIfAbsent(minuteStartMs, ignored -> new TradeAggregate())
                .add(realPrice, size, timestampNs);
        bufferedTrades.keySet().removeIf(date -> date.isBefore(eventDate.minusDays(1)));
        return null;
    }

    public synchronized List<VwapPoint> drainPendingIndicatorPoints() {
        List<VwapPoint> result = new ArrayList<>(pendingIndicatorPoints);
        pendingIndicatorPoints.clear();
        return result;
    }

    public synchronized void requestCurrentPoint(long timestampNs) {
        if (activeSeed != null && cumulativeVolume > 0 && timestampNs > 0) {
            pendingIndicatorPoints.add(new VwapPoint(timestampNs, currentVwap()));
        }
    }

    public synchronized Snapshot snapshot() {
        if (activeSeed == null || cumulativeVolume <= 0) {
            return null;
        }
        return new Snapshot(
                activeSeed.getSessionDate(),
                cumulativeVolume,
                cumulativeNotional);
    }

    private double currentVwap() {
        return cumulativeNotional / cumulativeVolume;
    }
}
