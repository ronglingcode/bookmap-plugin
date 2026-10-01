package com.bookmap.plugin.rong.miniviteapp.core.controllers;

import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.bookmap.plugin.rong.miniviteapp.models.Models.ExitPair;
import com.bookmap.plugin.rong.miniviteapp.models.Models.Snapshot;

/** Mirror: controllers/coreTargetExitRules.ts and coreTargetRule.ts. */
public final class CoreTargetExitRules {
    private CoreTargetExitRules() { }
    public static void check(Snapshot state, ExitPair pair, double price, boolean earlier) {
        if (!state.coreRuleEnabled || !earlier) return;
        Models.require(pair.originalPartial > 0, "missing original partial number for core-target protection");
        if (!state.hasPlan) {
            Models.require(pair.originalPartial <= 3, "missing active trade plan for protected partial");
            return;
        }
        int count = Double.isFinite(state.coreCount) ? Math.max(0, Math.min(7, (int) state.coreCount)) : 7;
        if (pair.originalPartial <= 3 || pair.originalPartial < 11 - count) return;
        Models.require(Models.positive(state.entryPrice), "missing original entry price");
        Models.require(Models.positive(state.coreTarget), "missing coreTarget for protected partial");
        boolean isLong = state.netQuantity > 0;
        Models.require(isLong ? state.coreTarget > state.entryPrice : state.coreTarget < state.entryPrice,
                "coreTarget is not on the profitable side of entry");
        double buffered = state.entryPrice + (state.coreTarget - state.entryPrice) * 0.9;
        Models.require(isLong ? price + 1e-9 >= buffered : price - 1e-9 <= buffered,
                "core target blocked exit");
    }
}
