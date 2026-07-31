package com.bookmap.plugin.rong;

public final class VwapUpdateDefinition {

    private final String symbol;
    private final double vwap;
    private final long effectiveTimeMs;
    private final long sentAtMs;

    public VwapUpdateDefinition(
            String symbol,
            double vwap,
            long effectiveTimeMs,
            long sentAtMs) {
        String cleanSymbol = SymbolUtils.cleanSymbol(symbol);
        if (cleanSymbol.isEmpty()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (!BookmapPriceNormalizer.isValidWirePrice(vwap)) {
            throw new IllegalArgumentException("VWAP must be a valid wire price");
        }
        if (effectiveTimeMs <= 0) {
            throw new IllegalArgumentException("effectiveTimeMs must be positive");
        }
        if (sentAtMs <= 0) {
            throw new IllegalArgumentException("sentAtMs must be positive");
        }

        this.symbol = cleanSymbol;
        this.vwap = vwap;
        this.effectiveTimeMs = effectiveTimeMs;
        this.sentAtMs = sentAtMs;
    }

    public String getSymbol() {
        return symbol;
    }

    public double getVwap() {
        return vwap;
    }

    public long getEffectiveTimeMs() {
        return effectiveTimeMs;
    }

    public long getSentAtMs() {
        return sentAtMs;
    }
}
