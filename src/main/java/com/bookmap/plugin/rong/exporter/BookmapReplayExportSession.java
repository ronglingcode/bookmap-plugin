package com.bookmap.plugin.rong.exporter;

import com.bookmap.plugin.rong.PluginLog;

import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;

/**
 * Shared Bookmap replay export session used by both the standalone exporter
 * plugin and the optional export mode inside the live Rong plugin.
 */
public class BookmapReplayExportSession {

    private static final long NS_PER_SECOND = 1_000_000_000L;

    private final String runId;
    private final String pluginName;
    private final String rawAlias;
    private final String symbol;
    private final InstrumentInfo instrumentInfo;
    private final BookmapExportWriter writer;
    private final int depthMinSize;
    private final Set<Long> trackedDepthLevels = new HashSet<>();

    private long lastTimestampNs;
    private final long initialTimestampNs;
    private long depthEvents;
    private long tradeEvents;
    private long bboEvents;
    private long snapshotEndEvents;

    private BookmapReplayExportSession(
            String runId,
            String pluginName,
            String rawAlias,
            String symbol,
            InstrumentInfo instrumentInfo,
            long initialTimestampNs,
            int depthMinSize,
            BookmapExportWriter writer) {
        this.runId = runId;
        this.pluginName = pluginName;
        this.rawAlias = rawAlias;
        this.symbol = symbol;
        this.instrumentInfo = instrumentInfo;
        this.initialTimestampNs = initialTimestampNs;
        this.lastTimestampNs = initialTimestampNs;
        this.depthMinSize = depthMinSize;
        this.writer = writer;
    }

    public static BookmapReplayExportSession open(
            String runId,
            String pluginName,
            String rawAlias,
            String symbol,
            InstrumentInfo instrumentInfo,
            long initialTimestampNs) {
        int depthMinSize = BookmapExportWriter.intProperty("bookmap.export.depthMinSize", 0, 0);
        int flushEvery = BookmapExportWriter.intProperty("bookmap.export.flushEvery", 1000, 1);

        BookmapReplayExportSession seed = new BookmapReplayExportSession(
                runId,
                pluginName,
                rawAlias,
                symbol,
                instrumentInfo,
                initialTimestampNs,
                depthMinSize,
                null);
        String sessionStart = seed.buildSessionStartEvent();

        try {
            BookmapExportWriter writer = BookmapExportWriter.open(runId, symbol, sessionStart, flushEvery);
            BookmapReplayExportSession session = new BookmapReplayExportSession(
                    runId,
                    pluginName,
                    rawAlias,
                    symbol,
                    instrumentInfo,
                    initialTimestampNs,
                    depthMinSize,
                    writer);
            writer.writeLine(sessionStart);
            PluginLog.info("[BacktestExporter] Initialized for " + symbol
                    + " via " + pluginName
                    + " depthMinSize=" + depthMinSize
                    + " metadata=" + writer.getMetadataPath());
            return session;
        } catch (IOException | RuntimeException e) {
            PluginLog.error("[BacktestExporter] Failed to initialize exporter for " + symbol, e);
            return null;
        }
    }

    public void stop() {
        if (writer == null) {
            return;
        }
        writer.writeLine(buildSessionEndEvent());
        writer.close();
        PluginLog.info("[BacktestExporter] Stopped for " + symbol
                + " via " + pluginName
                + " depth=" + depthEvents
                + " trades=" + tradeEvents
                + " bbo=" + bboEvents
                + " snapshotEnd=" + snapshotEndEvents);
    }

    public void onTimestamp(long timestampNs) {
        this.lastTimestampNs = timestampNs;
    }

    public void onDepth(boolean isBid, int price, int size) {
        if (writer == null || !shouldLogDepth(isBid, price, size)) {
            return;
        }
        depthEvents++;

        StringBuilder sb = startEvent("depth");
        appendStringField(sb, "side", isBid ? "bid" : "ask");
        appendIntField(sb, "price_tick", price);
        appendDoubleField(sb, "price", price * instrumentInfo.pips);
        appendIntField(sb, "size", size);
        sb.append('}');
        writer.writeLine(sb.toString());
    }

    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        if (writer == null || size <= 0) {
            return;
        }
        tradeEvents++;

        StringBuilder sb = startEvent("trade");
        appendDoubleField(sb, "price_level", price);
        appendDoubleField(sb, "price", price * instrumentInfo.pips);
        appendIntField(sb, "size", size);
        if (tradeInfo != null) {
            appendBooleanField(sb, "is_otc", tradeInfo.isOtc);
            appendBooleanField(sb, "is_bid_aggressor", tradeInfo.isBidAggressor);
            appendBooleanField(sb, "is_execution_start", tradeInfo.isExecutionStart);
            appendBooleanField(sb, "is_execution_end", tradeInfo.isExecutionEnd);
            appendStringField(sb, "aggressor_order_id", tradeInfo.aggressorOrderId);
            appendStringField(sb, "passive_order_id", tradeInfo.passiveOrderId);
        }
        sb.append('}');
        writer.writeLine(sb.toString());
    }

    public void onBbo(int bidPrice, int bidSize, int askPrice, int askSize) {
        if (writer == null) {
            return;
        }
        bboEvents++;

        StringBuilder sb = startEvent("bbo");
        appendIntField(sb, "bid_price_tick", bidPrice);
        appendDoubleField(sb, "bid_price", bidPrice * instrumentInfo.pips);
        appendIntField(sb, "bid_size", bidSize);
        appendIntField(sb, "ask_price_tick", askPrice);
        appendDoubleField(sb, "ask_price", askPrice * instrumentInfo.pips);
        appendIntField(sb, "ask_size", askSize);
        sb.append('}');
        writer.writeLine(sb.toString());
    }

    public void onSnapshotEnd() {
        if (writer == null) {
            return;
        }
        snapshotEndEvents++;
        StringBuilder sb = startEvent("snapshot_end");
        sb.append('}');
        writer.writeLine(sb.toString());
        writer.flush();
    }

    public void onRealtimeStart() {
        if (writer == null) {
            return;
        }
        StringBuilder sb = startEvent("realtime_start");
        sb.append('}');
        writer.writeLine(sb.toString());
        writer.flush();
    }

    private boolean shouldLogDepth(boolean isBid, int price, int size) {
        if (depthMinSize <= 0) {
            return true;
        }
        long key = depthKey(isBid, price);
        if (size >= depthMinSize) {
            trackedDepthLevels.add(key);
            return true;
        }
        if (trackedDepthLevels.contains(key)) {
            trackedDepthLevels.remove(key);
            return true;
        }
        return false;
    }

    private long depthKey(boolean isBid, int price) {
        return (((long) price) << 1) ^ (isBid ? 1L : 0L);
    }

    private String buildSessionStartEvent() {
        StringBuilder sb = startEvent("session_start");
        appendStringField(sb, "plugin", pluginName);
        appendStringField(sb, "alias", rawAlias);
        appendStringField(sb, "full_name", instrumentInfo != null ? instrumentInfo.fullName : "");
        appendStringField(sb, "exchange", instrumentInfo != null ? instrumentInfo.exchange : "");
        appendStringField(sb, "instrument_type", instrumentInfo != null ? instrumentInfo.type : "");
        appendDoubleField(sb, "pips", instrumentInfo != null ? instrumentInfo.pips : Double.NaN);
        appendDoubleField(sb, "multiplier", instrumentInfo != null ? instrumentInfo.multiplier : Double.NaN);
        appendDoubleField(sb, "size_multiplier", instrumentInfo != null ? instrumentInfo.sizeMultiplier : Double.NaN);
        appendBooleanField(sb, "is_full_depth", instrumentInfo != null && instrumentInfo.isFullDepth);
        appendStringField(sb, "output_root", BookmapExportWriter.resolveOutputRoot().toString());
        appendIntField(sb, "depth_min_size", depthMinSize);
        sb.append('}');
        return sb.toString();
    }

    private String buildSessionEndEvent() {
        StringBuilder sb = startEvent("session_end");
        appendLongField(sb, "depth_events", depthEvents);
        appendLongField(sb, "trade_events", tradeEvents);
        appendLongField(sb, "bbo_events", bboEvents);
        appendLongField(sb, "snapshot_end_events", snapshotEndEvents);
        sb.append('}');
        return sb.toString();
    }

    private StringBuilder startEvent(String type) {
        long tsNs = getEventTimeNs();
        StringBuilder sb = new StringBuilder(384);
        sb.append('{');
        sb.append("\"schema_version\":").append(BookmapExportWriter.SCHEMA_VERSION);
        appendStringField(sb, "type", type);
        appendStringField(sb, "run_id", runId);
        appendStringField(sb, "symbol", symbol);
        appendLongField(sb, "ts_ns", tsNs);
        appendStringField(sb, "ts_iso_utc", formatTimestampNs(tsNs));
        appendLongField(sb, "wall_clock_ms", System.currentTimeMillis());
        return sb;
    }

    private long getEventTimeNs() {
        if (lastTimestampNs > 0) {
            return lastTimestampNs;
        }
        if (initialTimestampNs > 0) {
            return initialTimestampNs;
        }
        return System.currentTimeMillis() * 1_000_000L;
    }

    private static String formatTimestampNs(long timestampNs) {
        long seconds = Math.floorDiv(timestampNs, NS_PER_SECOND);
        long nanos = Math.floorMod(timestampNs, NS_PER_SECOND);
        return Instant.ofEpochSecond(seconds, nanos).toString();
    }

    private static void appendStringField(StringBuilder sb, String name, String value) {
        sb.append(",\"").append(name).append("\":").append(BookmapExportWriter.jsonString(value));
    }

    private static void appendIntField(StringBuilder sb, String name, int value) {
        sb.append(",\"").append(name).append("\":").append(value);
    }

    private static void appendLongField(StringBuilder sb, String name, long value) {
        sb.append(",\"").append(name).append("\":").append(value);
    }

    private static void appendDoubleField(StringBuilder sb, String name, double value) {
        sb.append(",\"").append(name).append("\":");
        if (Double.isFinite(value)) {
            sb.append(Double.toString(value));
        } else {
            sb.append("null");
        }
    }

    private static void appendBooleanField(StringBuilder sb, String name, boolean value) {
        sb.append(",\"").append(name).append("\":").append(value);
    }
}
