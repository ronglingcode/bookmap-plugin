package com.bookmap.plugin.rong.miniviteapp;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Preserve causes in the UI without reflecting credentials or whole account responses. */
public final class ExecutionDiagnostics {
    private ExecutionDiagnostics() { }
    public static String sanitize(String text, String... secrets) {
        String result = text == null ? "" : text;
        for (String secret : secrets) if (secret != null && !secret.isEmpty()) result = result.replace(secret, "[redacted]");
        result = result.replaceAll("(?i)(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+", "$1 [redacted]")
                .replaceAll("(?i)([\\\"']?(?:access_?token|refresh_?token|client_?secret|appSecret|accountNumber)[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,}&]+)", "$1[redacted]")
                .replaceAll("(?i)(/accounts/)[^/?\\s\\\"]+", "$1[redacted]")
                .replaceAll("[\\r\\n\\t]+", " ");
        return result.length() > 2000 ? result.substring(0, 2000) + " [truncated]" : result;
    }
    public static String describe(Throwable error, String... secrets) {
        StringBuilder text = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (text.length() > 0) text.append("; caused by ");
            text.append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) text.append(": ").append(cause.getMessage());
            else if (cause.getStackTrace().length > 0) text.append(" at ").append(cause.getStackTrace()[0]);
        }
        return sanitize(text.toString(), secrets);
    }
}
