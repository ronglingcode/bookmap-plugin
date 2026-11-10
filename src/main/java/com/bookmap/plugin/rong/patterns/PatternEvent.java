package com.bookmap.plugin.rong.patterns;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import com.bookmap.plugin.rong.BookmapPriceNormalizer;
import com.google.gson.annotations.SerializedName;

/** Immutable semantic observation. Occurrence time and detection time are distinct. */
public final class PatternEvent {
    public enum SizeBasis { DISPLAYED_WALL, DISPLAYED_REMOVED }
    public enum TimestampProvenance { MARKET, FALLBACK }
    public enum Attribution { UNKNOWN, INFERRED_WITHDRAWAL, PROBABLE_CONSUMPTION, PROBABLE_MOVE }
    public enum Coverage { WARMUP, USABLE, GAP }

    public final String id, episodeKey, interactionId, instrumentAlias;
    public final long epoch, size, eventTimeNs, observedAtNs;
    public final int revision, priceTick;
    public final double tickSize, price;
    public final PatternEventType type;
    public final PatternSide side;
    public final PatternMeaning meaning;
    public final SizeBasis sizeBasis;
    public final PatternSizeCategory sizeCategory;
    public final TimestampProvenance timestampProvenance;
    public final Evidence evidence;

    private PatternEvent(Builder b) {
        instrumentAlias = text(b.alias, "alias");
        interactionId = text(b.interactionId, "interactionId");
        type = Objects.requireNonNull(b.type, "type");
        episodeKey = text(b.episodeKey == null ? type.name() + ":" + interactionId : b.episodeKey, "episodeKey");
        if (b.epoch <= 0 || b.revision <= 0 || b.size <= 0 || b.priceTick <= 0
                || b.eventTimeNs <= 0 || b.observedAtNs < b.eventTimeNs) {
            throw new IllegalArgumentException("Positive identity, quantity, price and ordered market times required");
        }
        side = Objects.requireNonNull(b.side, "side");
        meaning = Objects.requireNonNull(b.meaning, "meaning");
        if (side != type.side || meaning != type.meaning || !meaning.isCompatible(side)) {
            throw new IllegalArgumentException("Pattern type, side and meaning disagree");
        }
        epoch = b.epoch;
        revision = b.revision;
        size = b.size;
        sizeCategory = PatternSizeCategory.classify(size);
        priceTick = b.priceTick;
        tickSize = b.tickSize;
        price = BookmapPriceNormalizer.toWirePrice(priceTick, tickSize);
        eventTimeNs = b.eventTimeNs;
        observedAtNs = b.observedAtNs;
        sizeBasis = Objects.requireNonNull(b.sizeBasis, "sizeBasis");
        timestampProvenance = Objects.requireNonNull(b.provenance, "timestampProvenance");
        evidence = Objects.requireNonNull(b.evidence, "evidence");
        id = instrumentAlias + ":" + epoch + ":" + episodeKey;
    }

    public long eventTimeMs() { return eventTimeNs / 1_000_000L; }
    public long observedAtMs() { return observedAtNs / 1_000_000L; }

    public static Builder builder(String alias, long epoch, PatternEventType type, String interactionId) {
        return new Builder(alias, epoch, type, interactionId);
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    public static final class Builder {
        private final String alias, interactionId;
        private final long epoch;
        private final PatternEventType type;
        private String episodeKey;
        private int revision = 1, priceTick;
        private long size, eventTimeNs, observedAtNs;
        private double tickSize;
        private PatternSide side;
        private PatternMeaning meaning;
        private SizeBasis sizeBasis = SizeBasis.DISPLAYED_WALL;
        private TimestampProvenance provenance = TimestampProvenance.MARKET;
        private Evidence evidence = Evidence.builder().build();

        private Builder(String alias, long epoch, PatternEventType type, String interactionId) {
            this.alias = alias; this.epoch = epoch; this.type = Objects.requireNonNull(type);
            this.interactionId = interactionId; side = type.side; meaning = type.meaning;
        }
        public Builder episodeKey(String value) { episodeKey = value; return this; }
        public Builder revision(int value) { revision = value; return this; }
        public Builder size(long value, SizeBasis basis) { size = value; sizeBasis = basis; return this; }
        public Builder price(int tick, double pips) { priceTick = tick; tickSize = pips; return this; }
        public Builder times(long occurrenceNs, long observationNs) { eventTimeNs = occurrenceNs; observedAtNs = observationNs; return this; }
        public Builder classification(PatternSide value, PatternMeaning semantics) { side = value; meaning = semantics; return this; }
        public Builder provenance(TimestampProvenance value) { provenance = value; return this; }
        public Builder evidence(Evidence value) { evidence = value; return this; }
        public PatternEvent build() { return new PatternEvent(this); }
    }

    /** Typed rule evidence; supplemental strings are diagnostic only. */
    public static final class Evidence {
        @SerializedName("wallPhaseId") public final String wallPhaseId;
        @SerializedName("previousSize") public final long previousSize;
        @SerializedName("currentSize") public final long currentSize;
        @SerializedName("removedSize") public final long removedSize;
        @SerializedName("attributedTradeSize") public final long attributedTradeSize;
        @SerializedName("growthBaselineSize") public final long growthBaselineSize;
        @SerializedName("approachTimeNs") public final long approachTimeNs;
        @SerializedName("rejectionTimeNs") public final long rejectionTimeNs;
        @SerializedName("growthStartNs") public final long growthStartNs;
        @SerializedName("growthEndNs") public final long growthEndNs;
        @SerializedName("growthDurationNs") public final long growthDurationNs;
        @SerializedName("addedSize") public final long addedSize;
        @SerializedName("growthRatio") public final double growthRatio;
        @SerializedName("growthIncreaseCount") public final int growthIncreaseCount;
        @SerializedName("growthMaxGapNs") public final long growthMaxGapNs;
        @SerializedName("growthLargestStepSize") public final long growthLargestStepSize;
        @SerializedName("buyAggressor") public final Boolean buyAggressor;
        @SerializedName("attribution") public final Attribution attribution;
        @SerializedName("coverage") public final Coverage coverage;
        @SerializedName("metadata") public final Map<String, String> metadata;

        private Evidence(EvidenceBuilder b) {
            if (b.previousSize < 0 || b.currentSize < 0 || b.attributedTradeSize < 0
                    || b.growthBaselineSize < 0 || b.approachTimeNs < 0 || b.rejectionTimeNs < 0) {
                throw new IllegalArgumentException("Evidence quantities and times cannot be negative");
            }
            if (b.growthStartNs < 0 || b.growthEndNs < b.growthStartNs
                    || (b.growthStartNs == 0) != (b.growthEndNs == 0)
                    || b.growthStartNs > 0 && (b.growthBaselineSize <= 0 || b.currentSize < b.growthBaselineSize)) {
                throw new IllegalArgumentException("Growth evidence requires an ordered interval and positive baseline");
            }
            if (b.growthIncreaseCount < 0 || b.growthMaxGapNs < 0 || b.growthLargestStepSize < 0) {
                throw new IllegalArgumentException("Growth sequence evidence cannot be negative");
            }
            growthIncreaseCount = b.growthIncreaseCount; growthMaxGapNs = b.growthMaxGapNs;
            growthLargestStepSize = b.growthLargestStepSize;
            growthStartNs = b.growthStartNs; growthEndNs = b.growthEndNs;
            growthDurationNs = growthEndNs - growthStartNs;
            addedSize = b.growthStartNs > 0 ? b.currentSize - b.growthBaselineSize : 0;
            growthRatio = b.growthStartNs > 0 ? (double)addedSize / b.growthBaselineSize : 0;
            wallPhaseId = Objects.requireNonNull(b.wallPhaseId);
            previousSize = b.previousSize; currentSize = b.currentSize;
            removedSize = Math.max(0, previousSize - currentSize);
            attributedTradeSize = b.attributedTradeSize;
            growthBaselineSize = b.growthBaselineSize;
            approachTimeNs = b.approachTimeNs; rejectionTimeNs = b.rejectionTimeNs;
            buyAggressor = b.buyAggressor;
            attribution = Objects.requireNonNull(b.attribution);
            coverage = Objects.requireNonNull(b.coverage);
            Map<String, String> copy = new LinkedHashMap<>(b.metadata);
            copy.forEach((k, v) -> { Objects.requireNonNull(k); Objects.requireNonNull(v); });
            metadata = Collections.unmodifiableMap(copy);
        }
        public static EvidenceBuilder builder() { return new EvidenceBuilder(); }
    }

    public static final class EvidenceBuilder {
        private String wallPhaseId = "";
        private long previousSize, currentSize, attributedTradeSize, growthBaselineSize, approachTimeNs, rejectionTimeNs;
        private long growthStartNs, growthEndNs, growthMaxGapNs, growthLargestStepSize;
        private int growthIncreaseCount;
        private Boolean buyAggressor;
        private Attribution attribution = Attribution.UNKNOWN;
        private Coverage coverage = Coverage.WARMUP;
        private Map<String, String> metadata = Collections.emptyMap();
        public EvidenceBuilder wall(String id, long previous, long current) { wallPhaseId = id; previousSize = previous; currentSize = current; return this; }
        public EvidenceBuilder trades(long size, Boolean buy) { attributedTradeSize = size; buyAggressor = buy; return this; }
        public EvidenceBuilder attribution(Attribution value, Coverage status) { attribution = value; coverage = status; return this; }
        public EvidenceBuilder interaction(long baseline, long approachNs, long rejectionNs) { growthBaselineSize = baseline; approachTimeNs = approachNs; rejectionTimeNs = rejectionNs; return this; }
        public EvidenceBuilder growth(long baseline, long startNs, long endNs) {
            growthBaselineSize = baseline; growthStartNs = startNs; growthEndNs = endNs; return this;
        }
        public EvidenceBuilder growthSequence(int count, long maxGapNs, long largestStepSize) {
            growthIncreaseCount = count; growthMaxGapNs = maxGapNs; growthLargestStepSize = largestStepSize; return this;
        }
        public EvidenceBuilder metadata(Map<String, String> value) { metadata = value; return this; }
        public Evidence build() { return new Evidence(this); }
    }
}
