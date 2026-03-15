package com.bookmap.plugin.activetrader;

import java.util.ArrayDeque;
import java.util.Deque;

public class SwingLowDetector {

    private final int pivotN;
    private final int barSize;
    private final Deque<Double> barLows = new ArrayDeque<>();
    private double currentBarLow = Double.MAX_VALUE;
    private int currentBarCount = 0;
    private double lastSwingLow = Double.NaN;

    public SwingLowDetector(int pivotN, int barSize) {
        this.pivotN = pivotN;
        this.barSize = barSize;
    }

    public void addPrice(double price) {
        currentBarLow = Math.min(currentBarLow, price);
        currentBarCount++;

        if (currentBarCount >= barSize) {
            barLows.addLast(currentBarLow);
            int windowSize = 2 * pivotN + 1;
            while (barLows.size() > windowSize) {
                barLows.removeFirst();
            }
            if (barLows.size() == windowSize) {
                checkPivot();
            }
            currentBarLow = Double.MAX_VALUE;
            currentBarCount = 0;
        }
    }

    public double getLastSwingLow() {
        return lastSwingLow;
    }

    private void checkPivot() {
        Double[] window = barLows.toArray(new Double[0]);
        double candidate = window[pivotN];
        for (int i = 0; i < window.length; i++) {
            if (i != pivotN && window[i] < candidate) {
                return;
            }
        }
        lastSwingLow = candidate;
    }
}
