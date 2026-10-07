package com.bookmap.plugin.rong.signal;

import java.util.List;
import com.bookmap.plugin.rong.patterns.Direction;
import com.bookmap.plugin.rong.patterns.PatternEvent;

/** Immutable presentation snapshot; reading it never advances the market clock. */
public final class SignalComposerInspection {
    public final String notice, diagnostics;
    public final List<Section> sections;

    public SignalComposerInspection(String notice, String diagnostics, List<Section> sections) {
        this.notice = notice;
        this.diagnostics = diagnostics;
        this.sections = List.copyOf(sections);
    }

    public static final class Section {
        public final Direction direction;
        public final List<String> requirements;
        public final List<Event> bids, offers;

        public Section(Direction direction, List<String> requirements, List<Event> bids, List<Event> offers) {
            this.direction = direction;
            this.requirements = List.copyOf(requirements);
            this.bids = List.copyOf(bids);
            this.offers = List.copyOf(offers);
        }
    }

    public static final class Event {
        public final PatternEvent pattern;
        public final String status;

        public Event(PatternEvent pattern, String status) {
            this.pattern = pattern;
            this.status = status;
        }
    }
}
