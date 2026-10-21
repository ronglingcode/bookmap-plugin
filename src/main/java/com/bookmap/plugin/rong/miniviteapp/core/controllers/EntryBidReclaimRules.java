package com.bookmap.plugin.rong.miniviteapp.core.controllers;

import com.bookmap.plugin.rong.miniviteapp.models.Models;
import com.bookmap.plugin.rong.miniviteapp.models.Models.Snapshot;
import static com.bookmap.plugin.rong.miniviteapp.models.DomainJson.*;

/** Manual short-entry reference required before changing later profit targets. */
public final class EntryBidReclaimRules {
    private EntryBidReclaimRules() { }

    public static boolean hasMarkedBid(Snapshot state) {
        return state.netQuantity < 0
                && Models.positive(number(object(state.entryContext, "activeTrade"), "entryBidToReclaim"));
    }

    public static void check(Snapshot state, int partial) {
        if (state.netQuantity >= 0 || partial <= 3) return;
        Models.require(hasMarkedBid(state),
                "Mark entry bid to reclaim first: hover the broken bid and press I before adjusting partials #4 onward");
    }
}
