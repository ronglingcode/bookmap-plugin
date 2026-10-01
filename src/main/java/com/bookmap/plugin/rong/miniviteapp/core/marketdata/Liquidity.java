package com.bookmap.plugin.rong.miniviteapp.core.marketdata;

import java.util.List;

public final class Liquidity {
    private Liquidity() {}
    public static double calculateLiquidityScale(double price, List<Double> volumes, double lastPremarketVolume, double marketCapInMillions, boolean lockedAtMax) {
        if (lockedAtMax) return 1;
        if (volumes.isEmpty()) return 0;
        double threshold = marketCapInMillions * 1000, volume = volumes.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double dollars = price * volume;
        if (volume < lastPremarketVolume || volume < 250000) return 0;
        if (volumes.size() == 1) {
            if (dollars > Math.min(20000000, threshold) || volume > 10 * lastPremarketVolume || volume > 1000000) return 1;
            return dollars > 10000000 || dollars > threshold ? 0.35 : 0;
        }
        if (volume > 10 * lastPremarketVolume || volume > 1000000 || dollars > Math.min(20000000, threshold)) return 1;
        if (dollars > 10000000) return dollars / 20000000;
        return dollars > threshold ? 0.35 : 0;
    }
}
