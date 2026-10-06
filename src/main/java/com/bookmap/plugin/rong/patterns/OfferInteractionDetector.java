package com.bookmap.plugin.rong.patterns;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import com.bookmap.plugin.rong.signal.SignalComposerConfig;

/** Persistent offer approach, actual downward print, and event-time hold. */
public final class OfferInteractionDetector {
    private static final class Interaction {
        final String phaseId; final int price;
        long approachNs, rejectionNs, sequence;
        String interactionId;
        boolean completed, broken, away;
        Interaction(EventTimeWallTracker.Wall wall) { phaseId = wall.phaseId; price = wall.priceTick; }
    }
    private final String alias;
    private final double pips;
    private final SignalComposerConfig config;
    private final EventTimeWallTracker walls;
    private final Consumer<PatternEvent> output;
    private final Map<String, Interaction> interactions = new LinkedHashMap<>();
    private int lastTrade;
    public OfferInteractionDetector(String alias, double pips, SignalComposerConfig config,
            EventTimeWallTracker walls, Consumer<PatternEvent> output) {
        this.alias = alias; this.pips = pips; this.config = config; this.walls = walls; this.output = output;
    }
    private void refresh() {
        Iterator<Interaction> iterator = interactions.values().iterator();
        while (iterator.hasNext()) {
            Interaction i = iterator.next(); EventTimeWallTracker.Wall wall = walls.active(false, i.price);
            if (wall == null || !wall.phaseId.equals(i.phaseId) || wall.size < config.observationFloorSize) iterator.remove();
        }
        for (EventTimeWallTracker.Wall wall : walls.snapshot()) if (!wall.bid && wall.qualified && wall.size >= config.observationFloorSize)
            interactions.computeIfAbsent(wall.phaseId, key -> new Interaction(wall));
        while (interactions.size() > config.maxWallPhases) interactions.remove(interactions.keySet().iterator().next());
    }
    public void onTrade(int price, long nowNs, long epoch) {
        refresh();
        for (Interaction i : interactions.values()) {
            if (price > i.price) { i.broken = true; i.rejectionNs = 0; continue; }
            if (i.broken) continue;
            boolean near = price <= i.price && (long)i.price - price <= config.detectors.approachDistanceTicks;
            if (i.completed && !near) i.away = true;
            if (near && (i.approachNs == 0 || i.completed && i.away)
                    && (lastTrade == 0 || lastTrade < i.price)) {
                i.approachNs = nowNs; i.rejectionNs = 0; i.completed = false; i.away = false;
                i.interactionId = i.phaseId + ":offer:" + ++i.sequence;
            } else if (!i.completed && i.approachNs > 0 && nowNs > i.approachNs) {
                if ((long)i.price - price >= config.detectors.rejectionDistanceTicks) {
                    if (i.rejectionNs == 0) i.rejectionNs = nowNs;
                } else i.rejectionNs = 0;
            }
        }
        lastTrade = price; onTime(nowNs, epoch);
    }
    public void onTime(long nowNs, long epoch) {
        refresh();
        for (Interaction i : interactions.values()) {
            if (i.approachNs == 0 || i.broken || i.completed) continue;
            if (nowNs - i.approachNs > config.detectors.interactionWindowMs * 1_000_000L) {
                i.approachNs = i.rejectionNs = 0; continue;
            }
            if (i.rejectionNs == 0 || nowNs - i.rejectionNs < config.detectors.rejectionHoldMs * 1_000_000L) continue;
            EventTimeWallTracker.Wall wall = walls.active(false, i.price);
            output.accept(PatternEvent.builder(alias, epoch, PatternEventType.OFFER_REJECTION, i.interactionId)
                    .episodeKey("offer-rejection:" + i.interactionId).size(wall.size, PatternEvent.SizeBasis.DISPLAYED_WALL)
                    .price(i.price, pips).times(nowNs, nowNs)
                    .evidence(PatternEvent.Evidence.builder().wall(i.phaseId, wall.size, wall.size)
                            .interaction(0, i.approachNs, i.rejectionNs)
                            .attribution(PatternEvent.Attribution.UNKNOWN, PatternEvent.Coverage.USABLE).build()).build());
            i.completed = true;
        }
    }
    public void reset() { interactions.clear(); lastTrade = 0; }
}
