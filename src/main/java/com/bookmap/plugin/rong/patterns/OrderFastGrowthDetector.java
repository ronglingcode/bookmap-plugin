package com.bookmap.plugin.rong.patterns;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Repeated additions to displayed quantity at one price/side over market time. */
public final class OrderFastGrowthDetector {
    private static final class Level {
        final String phaseId;
        final boolean bid;
        final int price;
        long baselineSize, size, firstIncrease, lastIncrease, maxGap, largestStep, currentStep;
        long occurrence, episodeNumber, emittedSize;
        int updates, revision;
        Level(EventTimeWallTracker.Wall wall) {
            phaseId = wall.phaseId; bid = wall.bid; price = wall.priceTick; restart(wall.size);
        }
        void restart(long quantity) {
            baselineSize = size = quantity;
            firstIncrease = lastIncrease = maxGap = largestStep = currentStep = 0;
            occurrence = episodeNumber = emittedSize = 0; updates = revision = 0;
        }
    }
    private final String alias;
    private final double pips;
    private final PatternObservationConfig config;
    private final EventTimeWallTracker walls;
    private final Consumer<PatternEvent> output;
    private final Map<String, Level> levels = new LinkedHashMap<>();
    private long sequence;

    public OrderFastGrowthDetector(String alias, double pips, PatternObservationConfig config,
            EventTimeWallTracker walls, Consumer<PatternEvent> output) {
        this.alias = alias; this.pips = pips; this.config = config; this.walls = walls; this.output = output;
    }
    private long windowNs() { return config.detectors.fastGrowthWindowMs * 1_000_000L; }
    private long gapNs() { return config.detectors.fastGrowthMaxGapMs * 1_000_000L; }
    public void onDepth(boolean bid, int price, long size, long now) {
        String key = (bid ? "B:" : "O:") + price;
        EventTimeWallTracker.Wall wall = walls.active(bid, price);
        if (wall == null || size < PatternEventType.BID_FAST_GROWTH.minimumTrackedSize()) { levels.remove(key); return; }
        Level level = levels.get(key);
        if (level == null || !level.phaseId.equals(wall.phaseId)) { levels.put(key, new Level(wall)); return; }
        if (size == level.size) return; // Repeated snapshots do not count as additions or refresh the gap.
        if (size < level.size) { level.restart(size); return; }
        if (level.firstIncrease > 0 && (now - level.lastIncrease > gapNs() || now - level.firstIncrease > windowNs())) {
            level.restart(level.size);
        }
        long added = size - level.size;
        if (level.firstIncrease == 0) {
            level.firstIncrease = level.lastIncrease = now; level.updates = 1; level.currentStep = added;
        } else if (now > level.lastIncrease) {
            level.maxGap = Math.max(level.maxGap, now - level.lastIncrease);
            level.lastIncrease = now; level.updates++; level.currentStep = added;
        } else {
            // Multiple callbacks at one market timestamp are one step, not independent evidence of continuity.
            level.currentStep += added;
        }
        level.largestStep = Math.max(level.largestStep, level.currentStep); level.size = size;
    }
    private boolean qualifies(Level level) {
        long added = level.size - level.baselineSize;
        if (level.updates < config.detectors.fastGrowthMinUpdates
                || level.lastIncrease - level.firstIncrease < config.detectors.fastGrowthMinDurationMs * 1_000_000L
                || added < config.detectors.fastGrowthMinIncrease) return false;
        BigDecimal delta = BigDecimal.valueOf(added);
        return delta.compareTo(BigDecimal.valueOf(level.baselineSize).multiply(BigDecimal.valueOf(config.detectors.fastGrowthRatio))) >= 0
                && BigDecimal.valueOf(level.largestStep).compareTo(delta.multiply(
                        BigDecimal.valueOf(config.detectors.fastGrowthMaxSingleStepRatio))) <= 0;
    }
    public void onTime(long now, long epoch) {
        Iterator<Level> it = levels.values().iterator();
        while (it.hasNext()) {
            Level level = it.next(); EventTimeWallTracker.Wall wall = walls.active(level.bid, level.price);
            if (wall == null || !level.phaseId.equals(wall.phaseId)
                    || wall.size < PatternEventType.BID_FAST_GROWTH.minimumTrackedSize()) { it.remove(); continue; }
            if (wall.size < level.size || level.firstIncrease > 0 && now - level.lastIncrease > gapNs()) {
                level.restart(wall.size); continue;
            }
            if (!wall.qualified || !qualifies(level) || level.size <= level.emittedSize) continue;
            if (level.episodeNumber == 0) { level.episodeNumber = ++sequence; level.occurrence = level.lastIncrease; }
            PatternEventType type = level.bid ? PatternEventType.BID_FAST_GROWTH : PatternEventType.OFFER_FAST_GROWTH;
            String interaction = level.phaseId + ":fast-growth:" + level.episodeNumber;
            output.accept(PatternEvent.builder(alias, epoch, type, interaction)
                    .episodeKey("fast-growth:" + interaction).revision(++level.revision)
                    .size(level.size, PatternEvent.SizeBasis.DISPLAYED_WALL).price(level.price, pips)
                    .times(level.occurrence, now).evidence(PatternEvent.Evidence.builder()
                            .wall(level.phaseId, level.baselineSize, level.size)
                            .growth(level.baselineSize, level.firstIncrease, level.lastIncrease)
                            .growthSequence(level.updates, level.maxGap, level.largestStep)
                            .attribution(PatternEvent.Attribution.UNKNOWN, PatternEvent.Coverage.USABLE).build()).build());
            level.emittedSize = level.size;
        }
    }
    public void reset() { levels.clear(); sequence = 0; }
}
