package com.bookmap.plugin.rong.patterns;

import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;

final class WallBreakPatternDefinition extends AbstractDirectionalPatternDefinition {

    private static final long HOLD_MS = 3_000;
    private static final long PULLBACK_WINDOW_MS = 30_000;

    private final Map<String, Episode> episodes = new LinkedHashMap<>();

    WallBreakPatternDefinition(PatternType patternType, boolean bidWall) {
        super(patternType, bidWall);
    }

    @Override
    public Set<PatternDetailKey> requiredDetails() {
        return EnumSet.of(
                PatternDetailKey.WALL_QUALITY,
                PatternDetailKey.NEARBY_OPPOSING_LIQUIDITY,
                PatternDetailKey.CONFIGURED_LEVEL_ALIGNMENT,
                PatternDetailKey.VWAP_ALIGNMENT,
                PatternDetailKey.HOLD_OR_PULLBACK,
                PatternDetailKey.SWEEP_STACK);
    }

    @Override
    public void score(PatternCandidate candidate, PatternScoringContext context,
                      List<ScoreContribution> contributions) {
        double vwap = context.vwapTick();
        if (Double.isFinite(vwap) && vwap > 0) {
            boolean agrees = patternType.getDirection() == Direction.LONG
                    ? candidate.triggerPriceTick >= vwap
                    : candidate.triggerPriceTick <= vwap;
            contributions.add(new ScoreContribution(
                    agrees ? "break.vwap_agrees" : "break.vwap_disagrees",
                    agrees ? 5 : -10,
                    agrees ? "direction agrees with RTH VWAP" : "direction disagrees with RTH VWAP"));
        }
        if ("pullback_rebreak".equals(candidate.confirmation)) {
            contributions.add(new ScoreContribution(
                    "break.pullback_rebreak", 15, "pullback held and re-broke"));
        } else if ("hold".equals(candidate.confirmation)) {
            contributions.add(new ScoreContribution(
                    "break.hold", 10, "price held beyond wall for 3s"));
        }
        if (candidate.referenceWall.tradedAtClear >= candidate.referenceWall.peakSize) {
            contributions.add(new ScoreContribution(
                    "break.wall_volume", 10, "aggressor volume reached peak wall size"));
        }
        if (candidate.referenceWall.sweep) {
            contributions.add(new ScoreContribution(
                    "break.stacked_sweep", -15, "three or more stacked walls swept in 1s"));
        }
    }

    @Override
    public void reset() {
        episodes.clear();
    }

    @Override
    public void onWallCleared(WallSnapshot wall, PatternRuntimeContext context) {
        if (!matches(wall)) return;
        Episode episode = new Episode(wall);
        episodes.put(wall.phaseId, episode);
        updateHold(episode, context);
    }

    @Override
    public void onTrade(PatternTradeTick trade, PatternRuntimeContext context) {
        cleanup(context.nowMs());
        for (Episode episode : episodes.values()) {
            if (episode.expired(context.nowMs())) continue;
            int price = trade.priceTick;
            if (!bidWall) {
                if (price > episode.postClearExtreme) {
                    if (episode.pullbackSeen && !episode.pullbackEmitted) {
                        emit(episode, "pullback_rebreak", price, trade.eventTimeNs, trade.eventTimeMs, context);
                        episode.pullbackEmitted = true;
                    }
                    episode.postClearExtreme = price;
                } else if (price >= episode.wall.priceTick && price < episode.postClearExtreme) {
                    episode.pullbackSeen = true;
                } else if (price < episode.wall.priceTick) {
                    episode.pullbackSeen = false;
                }
            } else {
                if (price < episode.postClearExtreme) {
                    if (episode.pullbackSeen && !episode.pullbackEmitted) {
                        emit(episode, "pullback_rebreak", price, trade.eventTimeNs, trade.eventTimeMs, context);
                        episode.pullbackEmitted = true;
                    }
                    episode.postClearExtreme = price;
                } else if (price <= episode.wall.priceTick && price > episode.postClearExtreme) {
                    episode.pullbackSeen = true;
                } else if (price > episode.wall.priceTick) {
                    episode.pullbackSeen = false;
                }
            }
        }
    }

    @Override
    public void onBbo(PatternRuntimeContext context) {
        cleanup(context.nowMs());
        for (Episode episode : episodes.values()) updateHold(episode, context);
    }

    @Override
    public void onTime(PatternRuntimeContext context) {
        onBbo(context);
    }

    private void updateHold(Episode episode, PatternRuntimeContext context) {
        if (episode.holdEmitted || episode.expired(context.nowMs())) return;
        boolean beyond = bidWall
                ? context.bestAskTick() > 0 && context.bestAskTick() < episode.wall.priceTick
                : context.bestBidTick() > episode.wall.priceTick;
        if (!beyond) {
            episode.holdStartMs = 0;
            return;
        }
        if (episode.holdStartMs == 0) episode.holdStartMs = episode.wall.clearedAtMs;
        if (context.nowMs() - episode.holdStartMs >= HOLD_MS) {
            int trigger = bidWall ? context.bestAskTick() : context.bestBidTick();
            emit(episode, "hold", trigger, context.nowNs(), context.nowMs(), context);
            episode.holdEmitted = true;
        }
    }

    private void emit(Episode episode, String confirmation, int triggerPriceTick,
                      long eventTimeNs, long eventTimeMs, PatternRuntimeContext context) {
        context.emit(PatternCandidate.builder(
                        patternType,
                        patternType.name() + ":" + episode.wall.phaseId,
                        episode.wall)
                .triggerPriceTick(triggerPriceTick)
                .confirmation(confirmation)
                .event(eventTimeNs, eventTimeMs)
                .build());
    }

    private void cleanup(long nowMs) {
        Iterator<Episode> iterator = episodes.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().expired(nowMs)) iterator.remove();
        }
    }

    private static final class Episode {
        final WallSnapshot wall;
        int postClearExtreme;
        long holdStartMs;
        boolean holdEmitted;
        boolean pullbackSeen;
        boolean pullbackEmitted;

        Episode(WallSnapshot wall) {
            this.wall = wall;
            this.postClearExtreme = wall.priceTick;
        }

        boolean expired(long nowMs) {
            return nowMs - wall.clearedAtMs > PULLBACK_WINDOW_MS;
        }
    }
}
