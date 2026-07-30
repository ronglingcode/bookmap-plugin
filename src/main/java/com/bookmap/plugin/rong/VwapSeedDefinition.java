package com.bookmap.plugin.rong;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

public final class VwapSeedDefinition {

    public static final ZoneId NEW_YORK_TIME = ZoneId.of("America/New_York");
    public static final LocalTime HANDOFF_TIME = LocalTime.of(9, 5);

    private final String symbol;
    private final LocalDate sessionDate;
    private final long continueFromTimeMs;
    private final double cumulativeVolume;
    private final double cumulativeNotional;
    private final long sentAtMs;

    public VwapSeedDefinition(
            String symbol,
            LocalDate sessionDate,
            long continueFromTimeMs,
            double cumulativeVolume,
            double cumulativeNotional,
            long sentAtMs) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        if (cleanSymbol.isEmpty()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (sessionDate == null) {
            throw new IllegalArgumentException("sessionDate is required");
        }
        if (continueFromTimeMs <= 0) {
            throw new IllegalArgumentException("continueFromTimeMs must be positive");
        }
        if (!Double.isFinite(cumulativeVolume) || cumulativeVolume <= 0) {
            throw new IllegalArgumentException("cumulativeVolume must be positive and finite");
        }
        if (!Double.isFinite(cumulativeNotional) || cumulativeNotional <= 0) {
            throw new IllegalArgumentException("cumulativeNotional must be positive and finite");
        }
        if (!BookmapPriceNormalizer.isValidWirePrice(cumulativeNotional / cumulativeVolume)) {
            throw new IllegalArgumentException("seed VWAP must be a valid wire price");
        }

        Instant handoffInstant = Instant.ofEpochMilli(continueFromTimeMs);
        LocalDate handoffDate = handoffInstant.atZone(NEW_YORK_TIME).toLocalDate();
        LocalTime handoffTime = handoffInstant.atZone(NEW_YORK_TIME).toLocalTime();
        if (!sessionDate.equals(handoffDate) || !HANDOFF_TIME.equals(handoffTime)) {
            throw new IllegalArgumentException(
                    "continueFromTimeMs must be 9:05 AM New York time on sessionDate");
        }

        this.symbol = cleanSymbol;
        this.sessionDate = sessionDate;
        this.continueFromTimeMs = continueFromTimeMs;
        this.cumulativeVolume = cumulativeVolume;
        this.cumulativeNotional = cumulativeNotional;
        this.sentAtMs = sentAtMs;
    }

    public String getSymbol() {
        return symbol;
    }

    public LocalDate getSessionDate() {
        return sessionDate;
    }

    public long getContinueFromTimeMs() {
        return continueFromTimeMs;
    }

    public double getCumulativeVolume() {
        return cumulativeVolume;
    }

    public double getCumulativeNotional() {
        return cumulativeNotional;
    }

    public long getSentAtMs() {
        return sentAtMs;
    }

    public double getVwap() {
        return cumulativeNotional / cumulativeVolume;
    }
}
