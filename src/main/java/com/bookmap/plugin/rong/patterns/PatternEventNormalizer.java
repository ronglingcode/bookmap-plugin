package com.bookmap.plugin.rong.patterns;

import java.util.LinkedHashMap;
import java.util.Map;
import com.bookmap.plugin.rong.signal.SignalComposerConfig;

/** Converts observations only; no legacy scoring or trade eligibility is consulted. */
public final class PatternEventNormalizer {
    private static final class Episode {
        final long occurrenceNs; int revision;
        Episode(long occurrenceNs) { this.occurrenceNs = occurrenceNs; }
    }
    private final String alias;
    private final double pips;
    private final SignalComposerConfig config;
    private final Map<String, Episode> episodes = new LinkedHashMap<>();
    public PatternEventNormalizer(String alias, double pips, SignalComposerConfig config) {
        this.alias = alias; this.pips = pips; this.config = config;
    }
    PatternEvent normalize(PatternCandidate candidate, EventTimeWallTracker tracker, long epoch, long observedNs) {
        EventTimeWallTracker.Wall current = tracker.active(candidate.triggerWall.bid, candidate.triggerPriceTick);
        if (current == null || !current.qualified || current.size < config.observationFloorSize
                || !current.phaseId.equals(candidate.triggerWall.phaseId)) return null;
        PatternEventType type = PatternEventType.valueOf(candidate.patternType.name());
        Episode episode = episodes.computeIfAbsent(candidate.episodeKey, key -> new Episode(candidate.eventTimeNs));
        while (episodes.size() > config.maxEvents) episodes.remove(episodes.keySet().iterator().next());
        return PatternEvent.builder(alias, epoch, type, current.phaseId).episodeKey(candidate.episodeKey)
                .revision(++episode.revision).size(current.size, PatternEvent.SizeBasis.DISPLAYED_WALL)
                .price(current.priceTick, pips).times(episode.occurrenceNs, observedNs)
                .evidence(PatternEvent.Evidence.builder().wall(current.phaseId, current.size, current.size)
                        .attribution(PatternEvent.Attribution.UNKNOWN, PatternEvent.Coverage.USABLE)
                        .metadata(Map.of("referencePhaseId", candidate.referenceWall.phaseId,
                                "behavior", candidate.confirmation)).build()).build();
    }
    public void reset() { episodes.clear(); }
}
