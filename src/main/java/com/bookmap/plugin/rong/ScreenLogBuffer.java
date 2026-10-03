package com.bookmap.plugin.rong;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** UI-only history: related fill updates in a burst replace their previous summary. */
final class ScreenLogBuffer {
    private static final long BURST_GAP = TimeUnit.SECONDS.toNanos(5);
    private final int limit;
    private final List<Entry> entries = new ArrayList<>();

    ScreenLogBuffer(int limit) { this.limit = limit; }

    void append(String line, String group, long now) {
        if (group != null) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry previous = entries.get(i);
                if (group.equals(previous.group)) {
                    if (now - previous.updated <= BURST_GAP) entries.remove(i);
                    break;
                }
            }
        }
        while (entries.size() >= limit) entries.remove(0);
        entries.add(new Entry(line, group, now));
    }

    List<String> lines() { return entries.stream().map(entry -> entry.line).collect(Collectors.toList()); }
    void clear() { entries.clear(); }

    private static final class Entry {
        final String line, group;
        final long updated;
        Entry(String line, String group, long updated) { this.line = line; this.group = group; this.updated = updated; }
    }
}
