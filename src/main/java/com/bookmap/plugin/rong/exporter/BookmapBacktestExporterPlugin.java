package com.bookmap.plugin.rong.exporter;

import com.bookmap.plugin.rong.SymbolUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.BboListener;
import velox.api.layer1.simplified.CustomModuleAdapter;
import velox.api.layer1.simplified.DepthDataListener;
import velox.api.layer1.simplified.HistoricalModeListener;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.SnapshotEndListener;
import velox.api.layer1.simplified.TimeListener;
import velox.api.layer1.simplified.TradeDataListener;

@Layer1SimpleAttachable
@Layer1StrategyName("Rong Backtest Exporter")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION1)
public class BookmapBacktestExporterPlugin implements CustomModuleAdapter,
        DepthDataListener, TradeDataListener, TimeListener,
        SnapshotEndListener, BboListener, HistoricalModeListener {

    private static final DateTimeFormatter RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final String RUN_ID = LocalDateTime.now().format(RUN_ID_FORMAT);

    private BookmapReplayExportSession exportSession;

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        String symbol = SymbolUtils.cleanSymbol(alias != null ? alias : (info != null ? info.symbol : "UNKNOWN"));
        long initialTimestampNs = initialState != null ? initialState.getCurrentTime() : 0L;
        exportSession = BookmapReplayExportSession.open(
                RUN_ID,
                "Rong Backtest Exporter",
                alias,
                symbol,
                info,
                initialTimestampNs);
    }

    @Override
    public void stop() {
        if (exportSession != null) {
            exportSession.stop();
            exportSession = null;
        }
    }

    @Override
    public void onTimestamp(long timestampNs) {
        if (exportSession != null) {
            exportSession.onTimestamp(timestampNs);
        }
    }

    @Override
    public void onDepth(boolean isBid, int price, int size) {
        if (exportSession != null) {
            exportSession.onDepth(isBid, price, size);
        }
    }

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        if (exportSession != null) {
            exportSession.onTrade(price, size, tradeInfo);
        }
    }

    @Override
    public void onBbo(int bidPrice, int bidSize, int askPrice, int askSize) {
        if (exportSession != null) {
            exportSession.onBbo(bidPrice, bidSize, askPrice, askSize);
        }
    }

    @Override
    public void onSnapshotEnd() {
        if (exportSession != null) {
            exportSession.onSnapshotEnd();
        }
    }

    @Override
    public void onRealtimeStart() {
        if (exportSession != null) {
            exportSession.onRealtimeStart();
        }
    }
}
