package com.bookmap.plugin.rong;

import com.bookmap.plugin.rong.miniviteapp.core.marketdata.MarketClock;

/** Volume-weighted price over recorded prints from 04:00 Eastern, or from the first available print. */
final class ReplayVwap {
    private String date;
    private long firstObserved;
    private long volume;
    private double dollars;
    private boolean coversPremarketStart;

    void observe(long timeNs) {
        if (timeNs <= 0) return;
        MarketClock.Time time = MarketClock.marketTime(timeNs / 1_000_000L);
        if (!time.date.equals(date)) {
            date = time.date;
            firstObserved = timeNs;
            volume = 0;
            dollars = 0;
            coversPremarketStart = time.minutesSinceMarketOpen <= -330;
        }
    }

    void trade(double price, int size, long timeNs) {
        observe(timeNs);
        if (timeNs <= 0 || size <= 0) return;
        double minute = MarketClock.marketTime(timeNs / 1_000_000L).minutesSinceMarketOpen;
        if (minute < -330) return;
        volume += size;
        dollars += price * size;
    }

    double value() { return volume > 0 ? dollars / volume : Double.NaN; }
    long firstObserved() { return firstObserved; }
    boolean coversPremarketStart() { return coversPremarketStart; }
}
