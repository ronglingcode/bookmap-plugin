package com.bookmap.plugin.rong.miniviteapp.config;

import java.util.Set;

/** Native coverage is deliberately limited to cancel and closing actions. */
public final class ExecutionConfig {
    public static final int PROTOCOL_VERSION = 1;
    public static final long MAX_STATE_AGE_MS = 10_000;
    public static final long MAX_QUOTE_AGE_MS = 10_000;
    public static final long TOKEN_MARGIN_MS = 30_000;
    public static final Set<String> ORIGINS = Set.of(
            "https://tradingapp-84f28.web.app", "https://tradingapp-84f28.firebaseapp.com",
            "http://localhost:5173", "http://127.0.0.1:5173");
    private ExecutionConfig() { }
}
