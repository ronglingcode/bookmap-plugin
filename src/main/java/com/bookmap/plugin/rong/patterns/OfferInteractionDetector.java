package com.bookmap.plugin.rong.patterns;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import com.bookmap.plugin.rong.signal.SignalComposerConfig;

/** Persistent offer approach, actual downward print, and event-time hold. */
public final class OfferInteractionDetector {
    private static final class Interaction {
        final String phaseId; final int price;
        long approachNs, rejectionNs, sequence, baselineSize, lastSize, growthAtNs, growthOccurrenceNs, rejectionEventNs;
        int growthRevision, rejectionRevision;
        String interactionId;
        boolean completed, broken, away, composite;
        Interaction(EventTimeWallTracker.Wall wall) {
            phaseId = wall.phaseId; price = wall.priceTick; baselineSize = lastSize = wall.size;
        }
    }
    private final String alias;
    private final double pips;
    private final SignalComposerConfig config;
    private final EventTimeWallTracker walls;
    private final Consumer<PatternEvent> output;
    private final EventTimeTradeAttribution trades;
    private static final class Breakout {
        final EventTimeWallTracker.Clear clear; final PatternEvent.Evidence evidence; final long epoch;
        Breakout(EventTimeWallTracker.Clear clear, PatternEvent.Evidence evidence, long epoch) {
            this.clear = clear; this.evidence = evidence; this.epoch = epoch;
        }
    }
    private final List<Breakout> breakouts = new ArrayList<>();
    private final Map<String, Interaction> interactions = new LinkedHashMap<>();
    private int lastTrade;
    public OfferInteractionDetector(String alias, double pips, SignalComposerConfig config,
            EventTimeWallTracker walls, EventTimeTradeAttribution trades, Consumer<PatternEvent> output) {
        this.alias = alias; this.pips = pips; this.config = config; this.walls = walls; this.output = output;
        this.trades = trades;
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
                if (i.completed) { i.baselineSize = walls.active(false, i.price).size; i.growthAtNs = 0; }
                i.approachNs = nowNs; i.rejectionNs = 0; i.completed = false; i.away = false;
                i.composite = false; i.rejectionEventNs = 0; i.rejectionRevision = 0;
                i.interactionId = i.phaseId + ":offer:" + ++i.sequence;
            } else if (i.approachNs > 0 && nowNs > i.approachNs) {
                if ((long)i.price - price >= config.detectors.rejectionDistanceTicks) {
                    if (i.rejectionNs == 0 || i.growthAtNs > i.rejectionNs) i.rejectionNs = nowNs;
                } else i.rejectionNs = 0;
            }
        }
        lastTrade = price; onTime(nowNs, epoch);
    }
    public void onTime(long nowNs, long epoch) {
        Iterator<Breakout> pending = breakouts.iterator();
        while (pending.hasNext()) {
            Breakout item = pending.next();
            long printNs = trades.firstBreakout(item.clear, true, config.detectors.breakoutDistanceTicks);
            if (printNs > 0) {
                output.accept(PatternEvent.builder(alias, item.epoch, PatternEventType.OFFER_BREAKOUT, item.clear.phaseId)
                        .size(item.clear.removedSize, PatternEvent.SizeBasis.DISPLAYED_REMOVED).price(item.clear.priceTick, pips)
                        .times(printNs, nowNs).evidence(item.evidence).build()); pending.remove();
            } else if (nowNs - item.clear.occurrenceNs > config.detectors.breakoutWindowMs * 1_000_000L) pending.remove();
        }
        refresh();
        for (Interaction i : interactions.values()) {
            EventTimeWallTracker.Wall wall = walls.active(false, i.price);
            if (wall.size > i.lastSize && wall.size >= i.baselineSize * (1 + config.detectors.growthRatio)) {
                i.growthAtNs = nowNs;
                if (i.growthOccurrenceNs == 0) i.growthOccurrenceNs = nowNs;
                output.accept(PatternEvent.builder(alias, epoch, PatternEventType.OFFER_SIZE_INCREASE, i.phaseId)
                        .episodeKey("offer-growth:" + i.phaseId).revision(++i.growthRevision)
                        .size(wall.size, PatternEvent.SizeBasis.DISPLAYED_WALL).price(i.price, pips)
                        .times(i.growthOccurrenceNs, nowNs).evidence(PatternEvent.Evidence.builder()
                                .wall(i.phaseId, i.lastSize, wall.size).interaction(i.baselineSize, i.approachNs, 0)
                                .attribution(PatternEvent.Attribution.UNKNOWN, PatternEvent.Coverage.USABLE).build()).build());
            }
            i.lastSize = wall.size;
            if (i.approachNs == 0 || i.broken || i.completed && i.composite) continue;
            if (nowNs - i.approachNs > config.detectors.interactionWindowMs * 1_000_000L) {
                i.approachNs = i.rejectionNs = 0; continue;
            }
            if (i.rejectionNs == 0 || nowNs - i.rejectionNs < config.detectors.rejectionHoldMs * 1_000_000L) continue;
            boolean composite = i.growthAtNs > 0 && i.rejectionNs >= i.growthAtNs
                    && wall.size >= i.baselineSize * (1 + config.detectors.growthRatio);
            if (i.completed && !composite) continue;
            if (i.rejectionEventNs == 0) i.rejectionEventNs = nowNs;
            output.accept(PatternEvent.builder(alias, epoch, composite ? PatternEventType.OFFER_SIZE_INCREASING_REJECTION
                            : PatternEventType.OFFER_REJECTION, i.interactionId)
                    .revision(++i.rejectionRevision)
                    .episodeKey("offer-rejection:" + i.interactionId).size(wall.size, PatternEvent.SizeBasis.DISPLAYED_WALL)
                    .price(i.price, pips).times(i.rejectionEventNs, nowNs)
                    .evidence(PatternEvent.Evidence.builder().wall(i.phaseId, wall.size, wall.size)
                            .interaction(i.baselineSize, i.approachNs, i.rejectionNs)
                            .attribution(PatternEvent.Attribution.UNKNOWN, PatternEvent.Coverage.USABLE).build()).build());
            i.completed = true; i.composite = composite;
        }
    }
    public void onClear(EventTimeWallTracker.Clear clear, PatternEvent.Evidence evidence, long epoch) {
        if (clear.bid || clear.removedSize <= 0 || evidence.coverage != PatternEvent.Coverage.USABLE
                || evidence.attribution != PatternEvent.Attribution.PROBABLE_CONSUMPTION) return;
        breakouts.add(new Breakout(clear, evidence, epoch));
        while (breakouts.size() > config.maxWallPhases) breakouts.remove(0);
        onTime(clear.observedAtNs, epoch);
    }
    public void reset() { interactions.clear(); breakouts.clear(); lastTrade = 0; }
}
