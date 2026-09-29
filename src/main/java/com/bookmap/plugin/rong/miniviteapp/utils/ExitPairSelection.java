package com.bookmap.plugin.rong.miniviteapp.utils;

import com.bookmap.plugin.rong.miniviteapp.models.Models.ExitPair;
import java.util.List;

/** Mirror: ViteApp/src/utils/exitPairSelection.ts#getFirstSmallestQuantityExitPairIndex. */
public final class ExitPairSelection {
    private ExitPairSelection() { }
    public static int getFirstSmallestQuantityExitPairIndex(List<ExitPair> pairs) {
        int selected = -1; double smallest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < pairs.size(); i++) {
            if (pairs.get(i).quantity() < smallest) { selected = i; smallest = pairs.get(i).quantity(); }
        }
        return selected;
    }
}
