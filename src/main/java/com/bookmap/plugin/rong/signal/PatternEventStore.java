package com.bookmap.plugin.rong.signal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.bookmap.plugin.rong.patterns.PatternEvent;

/** Per-epoch history; delayed occurrence is not a backwards processing clock. */
public final class PatternEventStore {
    public enum Change { INSERTED, REVISED, DUPLICATE, REJECTED }
    private final String alias;
    private long epoch, watermarkNs;
    private final long retentionNs;
    private final int maxEvents;
    private List<PatternEvent> lastEvictions = Collections.emptyList();
    private final Map<String, PatternEvent> events = new LinkedHashMap<>();
    private static final Comparator<PatternEvent> ORDER = Comparator.comparingLong((PatternEvent e) -> e.eventTimeNs).thenComparing(e -> e.id);

    public PatternEventStore(String alias, long epoch) {
        this(alias, epoch, SignalComposerConfig.defaults());
    }
    public PatternEventStore(String alias, long epoch, SignalComposerConfig config) {
        if (alias == null || alias.isBlank() || epoch <= 0) throw new IllegalArgumentException("History requires alias and epoch");
        if (!config.valid) throw new IllegalArgumentException(config.error);
        this.alias = alias; this.epoch = epoch;
        retentionNs = Math.multiplyExact(config.historyRetentionMs, 1_000_000L); maxEvents = config.maxEvents;
    }
    public Change put(PatternEvent event, long processingNs) {
        Objects.requireNonNull(event);
        if (!alias.equals(event.instrumentAlias) || epoch != event.epoch || processingNs < watermarkNs
                || event.observedAtNs > processingNs
                || event.size < event.type.minimumTrackedSize()) return Change.REJECTED;
        PatternEvent previous = events.get(event.id);
        if (previous != null && (!previous.interactionId.equals(event.interactionId) || previous.eventTimeNs != event.eventTimeNs
                || previous.side != event.side || previous.meaning != event.meaning)) return Change.REJECTED;
        watermarkNs = processingNs;
        if (previous != null && event.revision <= previous.revision) { pruneInternal(); return Change.DUPLICATE; }
        events.put(event.id, event);
        pruneInternal();
        return previous == null ? Change.INSERTED : Change.REVISED;
    }
    public List<PatternEvent> snapshot() {
        List<PatternEvent> copy = new ArrayList<>(events.values()); copy.sort(ORDER);
        return Collections.unmodifiableList(copy);
    }
    public PatternEvent get(String id) { return events.get(id); }
    public PatternEvent getEpisode(String episodeKey) { return events.get(alias + ":" + epoch + ":" + episodeKey); }
    public long watermarkNs() { return watermarkNs; }
    public long epoch() { return epoch; }
    public int size() { return events.size(); }
    public List<PatternEvent> lastEvictions() { return lastEvictions; }
    public List<PatternEvent> prune(long processingNs) {
        if (processingNs < watermarkNs) throw new IllegalArgumentException("Processing time moved backwards; reset epoch first");
        watermarkNs = processingNs; pruneInternal(); return lastEvictions;
    }
    private void pruneInternal() {
        List<PatternEvent> removed = new ArrayList<>();
        long cutoff = watermarkNs - retentionNs;
        events.values().removeIf(event -> {
            if (event.eventTimeNs < cutoff) { removed.add(event); return true; }
            return false;
        });
        while (events.size() > maxEvents) {
            PatternEvent oldest = Collections.min(events.values(), ORDER);
            events.remove(oldest.id); removed.add(oldest);
        }
        lastEvictions = Collections.unmodifiableList(removed);
    }
    public void reset(long nextEpoch) {
        if (nextEpoch <= epoch) throw new IllegalArgumentException("A reset needs a new epoch");
        epoch = nextEpoch; watermarkNs = 0; events.clear(); lastEvictions = Collections.emptyList();
    }
}
