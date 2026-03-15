package com.bookmap.plugin.activetrader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class OrderWallTracker {

    private final TreeMap<Integer, Integer> askDepth = new TreeMap<>();
    private final Map<Integer, Integer> peakSizes = new HashMap<>();
    private final int wallThreshold;
    private final double consumedRatio;

    public OrderWallTracker(int wallThreshold, double consumedRatio) {
        this.wallThreshold = wallThreshold;
        this.consumedRatio = consumedRatio;
    }

    public void updateLevel(boolean isBid, int price, int size) {
        if (isBid) {
            return;
        }
        if (size == 0) {
            askDepth.remove(price);
        } else {
            askDepth.put(price, size);
            peakSizes.merge(price, size, Math::max);
        }
    }

    public List<WallInfo> getActiveWalls() {
        List<WallInfo> walls = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : peakSizes.entrySet()) {
            if (entry.getValue() >= wallThreshold) {
                int price = entry.getKey();
                int currentSize = askDepth.getOrDefault(price, 0);
                walls.add(new WallInfo(price, currentSize, entry.getValue()));
            }
        }
        return walls;
    }

    public boolean isConsumed(WallInfo wall) {
        return wall.currentSize <= wall.peakSize * consumedRatio;
    }

    public void removeWall(int priceTick) {
        peakSizes.remove(priceTick);
    }

    public void cleanup(int currentPriceTick) {
        int cutoff = currentPriceTick - 2000;
        Iterator<Map.Entry<Integer, Integer>> it = peakSizes.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getKey() < cutoff) {
                it.remove();
            }
        }
    }

    public static class WallInfo {
        public final int priceTick;
        public final int currentSize;
        public final int peakSize;

        public WallInfo(int priceTick, int currentSize, int peakSize) {
            this.priceTick = priceTick;
            this.currentSize = currentSize;
            this.peakSize = peakSize;
        }
    }
}
