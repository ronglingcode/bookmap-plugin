package com.bookmap.plugin.rong.patterns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class BookmapPatternSignal {

    public enum QualityTier {
        LOW,
        MEDIUM,
        HIGH,
        VERY_HIGH
    }

    private final String id;
    private final String episodeKey;
    private final String instrumentAlias;
    private final PatternType patternType;
    private final int triggerPriceTick;
    private final double triggerPrice;
    private final int referenceWallPriceTick;
    private final int referenceWallPeakSize;
    private final int score;
    private final QualityTier tier;
    private final List<ScoreContribution> contributions;
    private final long eventTimeNs;
    private final long createdAtMs;

    public BookmapPatternSignal(
            String episodeKey,
            String instrumentAlias,
            PatternType patternType,
            int triggerPriceTick,
            double triggerPrice,
            int referenceWallPriceTick,
            int referenceWallPeakSize,
            int score,
            List<ScoreContribution> contributions,
            long eventTimeNs,
            long createdAtMs) {
        this.id = UUID.randomUUID().toString();
        this.episodeKey = Objects.requireNonNull(episodeKey, "episodeKey");
        this.instrumentAlias = Objects.requireNonNull(instrumentAlias, "instrumentAlias");
        this.patternType = Objects.requireNonNull(patternType, "patternType");
        this.triggerPriceTick = triggerPriceTick;
        this.triggerPrice = triggerPrice;
        this.referenceWallPriceTick = referenceWallPriceTick;
        this.referenceWallPeakSize = referenceWallPeakSize;
        this.score = Math.max(0, Math.min(100, score));
        this.tier = tierFor(this.score);
        this.contributions = Collections.unmodifiableList(new ArrayList<>(contributions));
        this.eventTimeNs = eventTimeNs;
        this.createdAtMs = createdAtMs;
    }

    public String getId() { return id; }
    public String getEpisodeKey() { return episodeKey; }
    public String getInstrumentAlias() { return instrumentAlias; }
    public PatternType getPatternType() { return patternType; }
    public Direction getDirection() { return patternType.getDirection(); }
    public int getTriggerPriceTick() { return triggerPriceTick; }
    public double getTriggerPrice() { return triggerPrice; }
    public int getReferenceWallPriceTick() { return referenceWallPriceTick; }
    public int getReferenceWallPeakSize() { return referenceWallPeakSize; }
    public int getScore() { return score; }
    public QualityTier getTier() { return tier; }
    public List<ScoreContribution> getContributions() { return contributions; }
    public long getEventTimeNs() { return eventTimeNs; }
    public long getCreatedAtMs() { return createdAtMs; }

    public ScoreContribution strongestPositiveContribution() {
        return contributions.stream()
                .filter(contribution -> contribution.getPoints() > 0)
                .max(Comparator.comparingInt(ScoreContribution::getPoints))
                .orElse(null);
    }

    public ScoreContribution strongestNegativeContribution() {
        return contributions.stream()
                .filter(contribution -> contribution.getPoints() < 0)
                .min(Comparator.comparingInt(ScoreContribution::getPoints))
                .orElse(null);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "bookmap_pattern_signal");
        json.addProperty("id", id);
        json.addProperty("episodeKey", episodeKey);
        json.addProperty("symbol", instrumentAlias);
        json.addProperty("pattern", patternType.name().toLowerCase());
        json.addProperty("direction", getDirection().name().toLowerCase());
        json.addProperty("triggerPrice", triggerPrice);
        json.addProperty("triggerPriceTick", triggerPriceTick);
        json.addProperty("referenceWallPriceTick", referenceWallPriceTick);
        json.addProperty("referenceWallPeakSize", referenceWallPeakSize);
        json.addProperty("qualityScore", score);
        json.addProperty("qualityTier", tier.name().toLowerCase());
        json.addProperty("eventTimeNs", Long.toString(eventTimeNs));
        json.addProperty("timestamp", createdAtMs);
        JsonArray reasons = new JsonArray();
        for (ScoreContribution contribution : contributions) {
            JsonObject reason = new JsonObject();
            reason.addProperty("ruleId", contribution.getRuleId());
            reason.addProperty("points", contribution.getPoints());
            reason.addProperty("detail", contribution.getDetail());
            reasons.add(reason);
        }
        json.add("scoreContributions", reasons);
        return json;
    }

    private static QualityTier tierFor(int score) {
        if (score < 40) return QualityTier.LOW;
        if (score < 60) return QualityTier.MEDIUM;
        if (score < 80) return QualityTier.HIGH;
        return QualityTier.VERY_HIGH;
    }
}
