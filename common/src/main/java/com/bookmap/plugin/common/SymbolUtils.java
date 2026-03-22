package com.bookmap.plugin.common;

/**
 * Utility for normalizing Bookmap instrument aliases.
 *
 * Bookmap may return aliases like "SMCI:NASDAQ:STOCKS@BMD" — this strips
 * everything after the first "@" or ":" to produce a clean ticker (e.g. "SMCI").
 */
public class SymbolUtils {

    private SymbolUtils() {}

    /**
     * Strips exchange/venue suffixes from a Bookmap instrument alias.
     *
     * @param instrumentAlias raw alias from Bookmap (e.g. "SMCI:NASDAQ:STOCKS@BMD")
     * @return clean ticker symbol (e.g. "SMCI")
     */
    public static String cleanSymbol(String instrumentAlias) {
        String clean = instrumentAlias;
        int atIdx = clean.indexOf('@');
        if (atIdx >= 0) {
            clean = clean.substring(0, atIdx);
        }
        int colonIdx = clean.indexOf(':');
        if (colonIdx >= 0) {
            clean = clean.substring(0, colonIdx);
        }
        return clean;
    }
}
