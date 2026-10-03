package com.bookmap.plugin.rong;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Keeps recurring warnings from flooding the UI. File delivery happens independently. */
final class ScreenLogPolicy {
    private static final long REPEAT_INTERVAL = TimeUnit.SECONDS.toNanos(30);
    private static final int MAX_KEYS = 256, MAX_MESSAGE = 240;
    private final Map<String, Repeat> recent = new LinkedHashMap<>();

    String line(PluginLogEvent event, long now) {
        return line(event, now, true);
    }

    String line(PluginLogEvent event, long now, boolean suppressRepeats) {
        if (event.screenMessage == null || event.screenMessage.isEmpty()) return null;
        // Full action messages include action IDs: separate user actions must remain visible,
        // even when their concise results read the same.
        String key = event.symbol + "\n" + event.source + "\n" + event.message + "\n" + event.screenMessage;
        Repeat repeat = recent.get(key);
        if (suppressRepeats && repeat != null && now - repeat.shown < REPEAT_INTERVAL) {
            repeat.skipped++;
            return null;
        }
        String text = event.screenLine();
        if (text.length() > MAX_MESSAGE) text = text.substring(0, MAX_MESSAGE) + "… (details in log file)";
        if (repeat != null && repeat.skipped > 0) text += " (" + repeat.skipped + " repeats since last shown)";
        recent.remove(key);
        recent.put(key, new Repeat(now));
        if (recent.size() > MAX_KEYS) recent.remove(recent.keySet().iterator().next());
        return text;
    }

    void clear() { recent.clear(); }

    private static final class Repeat {
        final long shown;
        int skipped;
        Repeat(long shown) { this.shown = shown; }
    }
}
