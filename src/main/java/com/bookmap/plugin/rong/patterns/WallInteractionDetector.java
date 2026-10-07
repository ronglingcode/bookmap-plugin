package com.bookmap.plugin.rong.patterns;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import com.bookmap.plugin.rong.signal.SignalComposerConfig;

/** Symmetric percentage-based wall tests; offer growth and consumed breakouts stay distinct. */
public final class WallInteractionDetector {
    private static final class Interaction {
        final String phaseId;
        final int price;
        final long approachLimit, retreatMinimum;
        int extreme;
        long approachNs, retreatNs, lastRetreatTradeNs, sequence, baselineSize, lastSize;
        long growthAtNs, growthOccurrenceNs, holdEventNs;
        int growthRevision, holdRevision;
        String interactionId;
        boolean completed, broken, away, composite;
        Interaction(EventTimeWallTracker.Wall wall, SignalComposerConfig config) {
            phaseId = wall.phaseId; price = wall.priceTick; baselineSize = lastSize = wall.size;
            // Convert percentages to the natural trade grid once, without any fixed tick minimum.
            BigDecimal wallPrice = BigDecimal.valueOf(price);
            approachLimit = wallPrice.multiply(BigDecimal.valueOf(config.detectors.holdApproachRatio))
                    .setScale(0, RoundingMode.FLOOR).longValueExact();
            retreatMinimum = wallPrice.multiply(BigDecimal.valueOf(config.detectors.holdRetreatRatio))
                    .setScale(0, RoundingMode.CEILING).longValueExact();
        }
        void abandon() { approachNs = retreatNs = lastRetreatTradeNs = 0; }
    }
    private final boolean bid;
    private final String alias;
    private final double pips;
    private final SignalComposerConfig config;
    private final EventTimeWallTracker walls;
    private final Consumer<PatternEvent> output;
    private final EventTimeTradeAttribution trades;
    private static final class Breakout {
        final EventTimeWallTracker.Clear clear;
        final PatternEvent.Evidence evidence;
        final long epoch;
        Breakout(EventTimeWallTracker.Clear clear, PatternEvent.Evidence evidence, long epoch) {
            this.clear = clear; this.evidence = evidence; this.epoch = epoch;
        }
    }
    private final List<Breakout> breakouts = new ArrayList<>();
    private final Map<String, Interaction> interactions = new LinkedHashMap<>();
    private int lastTrade, bestBid, bestAsk;
    public WallInteractionDetector(boolean bid, String alias, double pips, SignalComposerConfig config,
            EventTimeWallTracker walls, EventTimeTradeAttribution trades, Consumer<PatternEvent> output) {
        this.bid = bid; this.alias = alias; this.pips = pips; this.config = config;
        this.walls = walls; this.output = output; this.trades = trades;
    }
    private boolean quoteBroken(int price) {
        return bid ? bestAsk > 0 && bestAsk <= price : bestBid > 0 && bestBid >= price;
    }
    private void refresh() {
        Iterator<Interaction> iterator = interactions.values().iterator();
        while (iterator.hasNext()) {
            Interaction i = iterator.next(); EventTimeWallTracker.Wall wall = walls.active(bid, i.price);
            if (wall == null || !wall.phaseId.equals(i.phaseId) || wall.size < config.observationFloorSize) iterator.remove();
            else if (wall.size < holdType().minimumTrackedSize()) i.abandon();
        }
        for (EventTimeWallTracker.Wall wall : walls.snapshot()) {
            if (wall.bid != bid || !wall.qualified || wall.size < config.observationFloorSize) continue;
            Interaction i = interactions.computeIfAbsent(wall.phaseId, key -> new Interaction(wall, config));
            if (quoteBroken(i.price)) { i.broken = true; i.abandon(); }
        }
        while (interactions.size() > config.maxWallPhases) interactions.remove(interactions.keySet().iterator().next());
    }
    private PatternEventType holdType() { return bid ? PatternEventType.BID_HOLD : PatternEventType.OFFER_HOLD; }
    public void onTrade(int price, long nowNs, long epoch) {
        refresh();
        for (Interaction i : interactions.values()) {
            if (bid ? price < i.price : price > i.price) { i.broken = true; i.abandon(); continue; }
            if (i.broken || walls.active(bid, i.price).size < holdType().minimumTrackedSize()) continue;
            if (i.approachNs > 0 && nowNs - i.approachNs > config.detectors.interactionWindowMs * 1_000_000L) i.abandon();
            long wallDistance = bid ? (long)price - i.price : (long)i.price - price;
            boolean near = wallDistance <= i.approachLimit;
            if (i.completed && !near) i.away = true;
            boolean approachedFromDefendedSide = lastTrade == 0 || (bid ? lastTrade > i.price : lastTrade < i.price);
            if (near && (i.approachNs == 0 || i.completed && i.away) && approachedFromDefendedSide) {
                if (i.completed) { i.baselineSize = walls.active(bid, i.price).size; i.growthAtNs = 0; }
                i.approachNs = nowNs; i.retreatNs = i.lastRetreatTradeNs = 0; i.extreme = price;
                i.completed = i.away = i.composite = false; i.holdEventNs = 0; i.holdRevision = 0;
                i.interactionId = i.phaseId + (bid ? ":bid:" : ":offer:") + ++i.sequence;
            } else if (i.approachNs > 0 && nowNs > i.approachNs) {
                i.extreme = bid ? Math.min(i.extreme, price) : Math.max(i.extreme, price);
                long retreat = bid ? (long)price - i.extreme : (long)i.extreme - price;
                if (retreat >= i.retreatMinimum) {
                    if (i.retreatNs == 0 || i.growthAtNs > i.retreatNs) i.retreatNs = nowNs;
                    i.lastRetreatTradeNs = nowNs;
                } else i.retreatNs = i.lastRetreatTradeNs = 0;
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
            EventTimeWallTracker.Wall wall = walls.active(bid, i.price);
            if (!bid && wall.size > i.lastSize && wall.size >= i.baselineSize * (1 + config.detectors.growthRatio)) {
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
            if (i.approachNs == 0 || i.broken || i.completed && (bid || i.composite)) continue;
            if (nowNs - i.approachNs > config.detectors.interactionWindowMs * 1_000_000L) { i.abandon(); continue; }
            // A timestamp/depth update alone cannot confirm an otherwise silent market.
            if (i.retreatNs == 0 || i.lastRetreatTradeNs - i.retreatNs < config.detectors.holdConfirmationMs * 1_000_000L) continue;
            boolean composite = !bid && i.growthAtNs > 0 && i.retreatNs >= i.growthAtNs
                    && wall.size >= i.baselineSize * (1 + config.detectors.growthRatio);
            if (i.completed && !composite) continue;
            if (i.holdEventNs == 0) i.holdEventNs = nowNs;
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("testExtremeTick", Integer.toString(i.extreme));
            metadata.put("holdApproachRatio", Double.toString(config.detectors.holdApproachRatio));
            metadata.put("holdRetreatRatio", Double.toString(config.detectors.holdRetreatRatio));
            output.accept(PatternEvent.builder(alias, epoch, composite ? PatternEventType.OFFER_SIZE_INCREASING_HOLD : holdType(), i.interactionId)
                    .revision(++i.holdRevision)
                    .episodeKey((bid ? "bid-hold:" : "offer-hold:") + i.interactionId).size(wall.size, PatternEvent.SizeBasis.DISPLAYED_WALL)
                    .price(i.price, pips).times(i.holdEventNs, nowNs)
                    .evidence(PatternEvent.Evidence.builder().wall(i.phaseId, wall.size, wall.size)
                            .interaction(i.baselineSize, i.approachNs, i.retreatNs).metadata(metadata)
                            .attribution(PatternEvent.Attribution.UNKNOWN, PatternEvent.Coverage.USABLE).build()).build());
            i.completed = true; i.composite = composite;
        }
    }
    public void onClear(EventTimeWallTracker.Clear clear, PatternEvent.Evidence evidence, long epoch) {
        if (bid || clear.bid || clear.removedSize <= 0 || evidence.coverage != PatternEvent.Coverage.USABLE
                || evidence.attribution != PatternEvent.Attribution.PROBABLE_CONSUMPTION) return;
        breakouts.add(new Breakout(clear, evidence, epoch));
        while (breakouts.size() > config.maxWallPhases) breakouts.remove(0);
        onTime(clear.observedAtNs, epoch);
    }
    public void onBbo(int bidTick, int askTick) {
        bestBid = bidTick; bestAsk = askTick; refresh();
    }
    public void reset() { interactions.clear(); breakouts.clear(); lastTrade = bestBid = bestAsk = 0; }
}
