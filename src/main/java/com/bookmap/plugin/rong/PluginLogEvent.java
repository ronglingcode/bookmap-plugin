package com.bookmap.plugin.rong;

import com.bookmap.plugin.rong.miniviteapp.ExecutionDiagnostics;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/** One captured event shared by the screen and local file sinks. */
final class PluginLogEvent {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");
    final OffsetDateTime timestamp;
    final String session, symbol, source, message;

    PluginLogEvent(OffsetDateTime timestamp, String session, String symbol, String source, String message) {
        this.timestamp = timestamp;
        this.session = session;
        this.symbol = clean(symbol);
        this.source = clean(source);
        this.message = clean(message);
    }

    private static String clean(String value) {
        // Existing runtime diagnostics also redact the actual credential values before reaching here.
        return ExecutionDiagnostics.sanitize(value).replaceAll("(?i)([?&](?:code|apiKey|key|client_secret|access_token|refresh_token)=)[^&#\\s]+", "$1[redacted]").trim();
    }

    private String content() {
        return (symbol.isEmpty() ? "" : " " + symbol) + (source.isEmpty() ? "" : " [" + source + "]") + " " + message;
    }

    String screenLine() { return timestamp.format(TIME) + content(); }
    String fileLine() { return timestamp.format(STAMP) + " [session=" + session + "]" + content(); }
}
