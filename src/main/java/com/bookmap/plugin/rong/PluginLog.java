package com.bookmap.plugin.rong;

/**
 * Displays action messages in Bookmap's bmtrader Logs window only.
 * Nothing is written to files or stdout/stderr, which Bookmap can capture
 * in its own log files.
 */
public class PluginLog {

    private PluginLog() {}

    public static void action(String msg) {
        ActionLogWindow.append("", "", msg);
    }

    public static void action(String symbol, String msg) {
        action(symbol, "", msg);
    }

    public static void action(String symbol, String source, String msg) {
        String cleanSymbol = symbol == null ? "" : symbol.trim();
        String cleanSource = source == null ? "" : source.trim();
        ActionLogWindow.append(cleanSymbol, cleanSource, msg);
    }
}
