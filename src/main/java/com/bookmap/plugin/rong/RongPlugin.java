package com.bookmap.plugin.rong;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.bookmap.plugin.rong.exporter.BookmapReplayExportSession;
import com.bookmap.plugin.rong.orderwall.OrderWallChangeEvent;
import com.bookmap.plugin.rong.orderwall.OrderWallChangePainter;
import com.bookmap.plugin.rong.orderwall.OrderWallChangeSound;
import com.bookmap.plugin.rong.orderwall.OrderWallChangeStore;
import com.bookmap.plugin.rong.orderwall.OrderWallChangeTracker;
import com.bookmap.plugin.rong.orderwall.OrderWallLabelPainter;
import com.bookmap.plugin.rong.orderwall.OrderWallLabelStore;
import com.bookmap.plugin.rong.orderwall.OrderWallLabelTracker;
import com.bookmap.plugin.rong.orderwall.OrderWallTracker;
import com.bookmap.plugin.rong.pricelines.ChartClickHandler;
import com.bookmap.plugin.rong.pricelines.PriceLinePainter;
import com.bookmap.plugin.rong.pricelines.PriceLineStore;
import com.bookmap.plugin.rong.tradebuttons.TradeButtonWindow;

import velox.api.layer1.simplified.CustomSettingsPanelProvider;
import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.messages.Layer1ApiSoundAlertMessage;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyScreenSpacePainter;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.BboListener;
import velox.api.layer1.simplified.CustomModuleAdapter;
import velox.api.layer1.simplified.DepthDataListener;
import velox.api.layer1.simplified.HistoricalModeListener;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.SnapshotEndListener;
import velox.api.layer1.simplified.TimeListener;
import velox.api.layer1.simplified.TradeDataListener;
import velox.gui.StrategyPanel;


@Layer1SimpleAttachable
@Layer1StrategyName("Rong")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION1)
public class RongPlugin implements CustomModuleAdapter,
        DepthDataListener, TradeDataListener, TimeListener,
        SnapshotEndListener, BboListener, HistoricalModeListener,
        CustomSettingsPanelProvider, ReplayExportConfig.ChangeListener {

    private static final int WS_PORT = 8765;
    private static final DateTimeFormatter EXPORT_RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final String EXPORT_RUN_ID = LocalDateTime.now().format(EXPORT_RUN_ID_FORMAT);
    private static final int WALL_THRESHOLD = 500_000;
    private static final double WALL_CONSUMED_RATIO = 0.10;
    private static final double ORDERBOOK_PERCENTILE = 90;
    private static final int ORDERBOOK_INTERVAL_MS = 1000;
    private static final double WALL_LABEL_PERCENTILE = 95.0;
    private static final int WALL_LABEL_RETAIN_TICKS = 2_000;
    private static final int WALL_LABEL_REFRESH_MS = 200;
    private static final int WALL_CHANGE_THRESHOLD = 5_000;
    private static final double WALL_CHANGE_REMAINING_RATIO = 0.50;
    private static final long WALL_CHANGE_DECISION_DELAY_MS = 500;
    private static final byte[] WALL_CHANGE_SOUND = OrderWallChangeSound.createAlertSound();

    // Shared WebSocket server across all symbol instances
    private static SignalWebSocketServer sharedServer;
    private static int instanceCount = 0;
    private static ChartClickHandler chartClickHandler;
    private static PriceLineStore priceLineStore;
    private static PriceLinePainter priceLinePainter;
    private static OrderWallLabelStore wallLabelStore;
    private static OrderWallLabelPainter wallLabelPainter;
    private static OrderWallChangeStore wallChangeStore;
    private static OrderWallChangePainter wallChangePainter;
    private static IndicatorConfig indicatorConfig;
    private static ReplayExportConfig replayExportConfig;
    private static PremarketTracker premarketTracker;
    private static KeyLevelManager keyLevelManager;
    private static CamPivotTracker camPivotTracker;
    private static ExitOrderManager exitOrderManager;

    private String rawAlias;
    private String alias;
    private Api api;
    private OrderWallTracker wallTracker;
    private InstrumentInfo instrumentInfo;
    private OrderBookState orderBook;
    private OrderWallLabelTracker wallLabelTracker;
    private OrderWallChangeTracker wallChangeTracker;
    private boolean wallLabelsDirty;
    private long lastWallLabelRefreshMs;
    private long lastTimestampNs;
    private long initialTimestampNs;
    private TradeButtonWindow tradeButtonWindow;
    private volatile BookmapReplayExportSession replayExportSession;

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        this.rawAlias = alias;
        String cleanAlias = SymbolUtils.cleanSymbol(alias);
        this.alias = cleanAlias;
        this.api = api;
        this.instrumentInfo = info;
        this.initialTimestampNs = initialState != null ? initialState.getCurrentTime() : 0L;
        this.lastTimestampNs = initialTimestampNs;
        this.orderBook = new OrderBookState();
        this.wallTracker = new OrderWallTracker(WALL_THRESHOLD, WALL_CONSUMED_RATIO);

        synchronized (RongPlugin.class) {
            if (sharedServer == null) {
                sharedServer = new SignalWebSocketServer(WS_PORT, ORDERBOOK_PERCENTILE, ORDERBOOK_INTERVAL_MS);
                sharedServer.start();
                ActionLogWindow.showWindow();
                PluginLog.info("[Rong] Shared WebSocket server started on port " + WS_PORT);
            }
            if (chartClickHandler == null) {
                chartClickHandler = new ChartClickHandler(sharedServer);
            }
            if (priceLineStore == null) {
                priceLineStore = new PriceLineStore();
                priceLinePainter = new PriceLinePainter(priceLineStore);
                indicatorConfig = new IndicatorConfig();
                replayExportConfig = new ReplayExportConfig();
                wallLabelStore = new OrderWallLabelStore();
                wallChangeStore = new OrderWallChangeStore();
                wallLabelPainter = new OrderWallLabelPainter(wallLabelStore, indicatorConfig, wallChangeStore);
                wallChangePainter = new OrderWallChangePainter(wallChangeStore, indicatorConfig);
                premarketTracker = new PremarketTracker(priceLineStore, indicatorConfig);
                keyLevelManager = new KeyLevelManager(priceLineStore);
                sharedServer.registerKeyLevelConfigListener(keyLevelManager);
                exitOrderManager = new ExitOrderManager(priceLineStore);
                sharedServer.registerExitOrderPairsConfigListener(exitOrderManager);
                camPivotTracker = new CamPivotTracker(priceLineStore, indicatorConfig);
            }
            instanceCount++;
        }
        replayExportConfig.addChangeListener(this);
        this.wallLabelTracker = new OrderWallLabelTracker(
                cleanAlias, info.pips, wallLabelStore, WALL_LABEL_PERCENTILE, WALL_LABEL_RETAIN_TICKS);
        this.wallChangeTracker = new OrderWallChangeTracker(
                cleanAlias,
                info.pips,
                WALL_CHANGE_THRESHOLD,
                WALL_CHANGE_REMAINING_RATIO,
                WALL_CHANGE_DECISION_DELAY_MS,
                this::handleWallChangeEvent);
        sharedServer.registerSymbol(cleanAlias, orderBook, info.pips);
        chartClickHandler.registerSymbol(cleanAlias, info.pips);
        priceLinePainter.registerInstrument(cleanAlias);
        wallLabelPainter.registerInstrument(cleanAlias);
        wallChangePainter.registerInstrument(cleanAlias);

        // Register ScreenSpacePainter to receive chart coordinate mappings
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, "clickHandler_" + cleanAlias)
                .setScreenSpacePainterFactory(chartClickHandler)
                .setIsAdd(true)
                .build());

        // Register ScreenSpacePainter for drawing price lines
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, "priceLines_" + cleanAlias)
                .setScreenSpacePainterFactory(priceLinePainter)
                .setIsAdd(true)
                .build());
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, OrderWallLabelPainter.PAINTER_NAME_PREFIX + cleanAlias)
                .setScreenSpacePainterFactory(wallLabelPainter)
                .setIsAdd(true)
                .build());
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, OrderWallChangePainter.PAINTER_NAME_PREFIX + cleanAlias)
                .setScreenSpacePainterFactory(wallChangePainter)
                .setIsAdd(true)
                .build());

        // Draw predefined key price levels for this instrument
        if (keyLevelManager != null) {
            keyLevelManager.onInstrumentInitialized(cleanAlias, info.pips);
        }
        if (exitOrderManager != null) {
            exitOrderManager.onInstrumentInitialized(cleanAlias, info.pips);
        }

        // Fetch cam pivots + premarket high/low from EdgeDesk API (runs on background thread)
        IndicatorDataFetcher.fetch(cleanAlias, info.pips, camPivotTracker, premarketTracker);

        tradeButtonWindow = new TradeButtonWindow(cleanAlias, sharedServer);

        if (replayExportConfig.isEnabled()) {
            startReplayExport();
        }

        PluginLog.info("[Rong] Plugin initialized for " + cleanAlias);
    }

    @Override
    public void stop() {
        if (replayExportConfig != null) {
            replayExportConfig.removeChangeListener(this);
        }
        stopReplayExport();
        if (wallChangeTracker != null) {
            wallChangeTracker.shutdown();
            wallChangeTracker = null;
        }
        if (tradeButtonWindow != null) {
            tradeButtonWindow.dispose();
            tradeButtonWindow = null;
        }
        if (chartClickHandler != null) {
            chartClickHandler.unregisterSymbol(alias);
        }
        if (priceLinePainter != null) {
            priceLinePainter.unregisterInstrument(alias);
        }
        if (wallLabelPainter != null) {
            wallLabelPainter.unregisterInstrument(alias);
        }
        if (wallChangePainter != null) {
            wallChangePainter.unregisterInstrument(alias);
        }
        // Unregister ScreenSpacePainters
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, "clickHandler_" + alias)
                .setScreenSpacePainterFactory(chartClickHandler)
                .setIsAdd(false)
                .build());
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, "priceLines_" + alias)
                .setScreenSpacePainterFactory(priceLinePainter)
                .setIsAdd(false)
                .build());
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, OrderWallLabelPainter.PAINTER_NAME_PREFIX + alias)
                .setScreenSpacePainterFactory(wallLabelPainter)
                .setIsAdd(false)
                .build());
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, OrderWallChangePainter.PAINTER_NAME_PREFIX + alias)
                .setScreenSpacePainterFactory(wallChangePainter)
                .setIsAdd(false)
                .build());

        if (premarketTracker != null) {
            premarketTracker.unregister(alias);
        }
        if (camPivotTracker != null) {
            camPivotTracker.unregister(alias);
        }
        if (keyLevelManager != null) {
            keyLevelManager.onInstrumentStopped(alias);
        }
        if (exitOrderManager != null) {
            exitOrderManager.onInstrumentStopped(alias);
        }
        if (priceLineStore != null) {
            priceLineStore.clearAll(alias);
        }
        if (wallLabelStore != null) {
            wallLabelStore.clearAll(alias);
        }
        if (wallChangeStore != null) {
            wallChangeStore.clearAll(alias);
        }
        if (sharedServer != null) {
            sharedServer.unregisterSymbol(alias);
        }
        synchronized (RongPlugin.class) {
            instanceCount--;
            if (instanceCount <= 0 && sharedServer != null) {
                ChartClickHandler.removeAwtListener();
                if (premarketTracker != null) {
                    premarketTracker.shutdown();
                    premarketTracker = null;
                }
                if (camPivotTracker != null) {
                    camPivotTracker.shutdown();
                    camPivotTracker = null;
                }
                if (keyLevelManager != null) {
                    sharedServer.unregisterKeyLevelConfigListener(keyLevelManager);
                    keyLevelManager.shutdown();
                    keyLevelManager = null;
                }
                if (exitOrderManager != null) {
                    sharedServer.unregisterExitOrderPairsConfigListener(exitOrderManager);
                    exitOrderManager.shutdown();
                    exitOrderManager = null;
                }
                if (wallLabelPainter != null) {
                    wallLabelPainter.shutdown();
                }
                if (wallChangePainter != null) {
                    wallChangePainter.shutdown();
                }
                ActionLogWindow.dispose();
                sharedServer.shutdown();
                sharedServer = null;
                chartClickHandler = null;
                priceLineStore = null;
                priceLinePainter = null;
                wallLabelStore = null;
                wallLabelPainter = null;
                wallChangeStore = null;
                wallChangePainter = null;
                indicatorConfig = null;
                replayExportConfig = null;
                instanceCount = 0;
                PluginLog.info("[Rong] Shared WebSocket server shut down");
            }
        }
        PluginLog.info("[Rong] Plugin stopped for " + alias);
    }

    @Override
    public StrategyPanel[] getCustomSettingsPanels() {
        return new StrategyPanel[] {
            new IndicatorSettingsPanel(indicatorConfig),
            new ReplayExportSettingsPanel(replayExportConfig)
        };
    }

    @Override
    public void onDepth(boolean isBid, int price, int size) {
        BookmapReplayExportSession exportSession = replayExportSession;
        if (exportSession != null) {
            exportSession.onDepth(isBid, price, size);
        }
        if (wallChangeTracker != null) {
            wallChangeTracker.onDepth(isBid, price, size, getEventTimeNs());
        }
        orderBook.update(isBid, price, size);
        wallTracker.updateLevel(isBid, price, size);
        if (wallLabelTracker != null && wallLabelTracker.onDepth(orderBook, isBid, price, size, getEventTimeNs())) {
            wallLabelsDirty = true;
            refreshWallLabelsIfNeeded(false);
        }
    }

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        BookmapReplayExportSession exportSession = replayExportSession;
        if (exportSession != null) {
            exportSession.onTrade(price, size, tradeInfo);
        }
        if (wallChangeTracker != null) {
            wallChangeTracker.onTrade((int) Math.round(price), size, tradeInfo);
        }
        double realPrice = price * instrumentInfo.pips;
        int priceTick = (int) price;

        checkBreakout(realPrice);
        wallTracker.cleanup(priceTick);
        if (wallLabelTracker != null && wallLabelTracker.cleanup(priceTick)) {
            wallLabelsDirty = true;
        }
        refreshWallLabelsIfNeeded(true);

        if (premarketTracker != null) {
            premarketTracker.onTrade(alias, price, realPrice);
        }
    }

    @Override
    public void onTimestamp(long timestampNs) {
        this.lastTimestampNs = timestampNs;
        BookmapReplayExportSession exportSession = replayExportSession;
        if (exportSession != null) {
            exportSession.onTimestamp(timestampNs);
        }
        if (premarketTracker != null) {
            premarketTracker.setTimestamp(timestampNs);
        }
    }

    @Override
    public void onBbo(int bidPrice, int bidSize, int askPrice, int askSize) {
        BookmapReplayExportSession exportSession = replayExportSession;
        if (exportSession != null) {
            exportSession.onBbo(bidPrice, bidSize, askPrice, askSize);
        }
    }

    @Override
    public void onSnapshotEnd() {
        BookmapReplayExportSession exportSession = replayExportSession;
        if (exportSession != null) {
            exportSession.onSnapshotEnd();
        }
        if (wallChangeTracker != null) {
            wallChangeTracker.markReady();
        }
    }

    @Override
    public void onRealtimeStart() {
        BookmapReplayExportSession exportSession = replayExportSession;
        if (exportSession != null) {
            exportSession.onRealtimeStart();
        }
        if (wallChangeTracker != null) {
            wallChangeTracker.markReady();
        }
    }

    @Override
    public void onReplayExportConfigChanged(boolean enabled) {
        if (enabled) {
            startReplayExport();
        } else {
            stopReplayExport();
        }
    }

    private void checkBreakout(double currentPrice) {
        List<OrderWallTracker.WallInfo> walls = wallTracker.getActiveWalls();
        for (OrderWallTracker.WallInfo wall : walls) {
            double wallRealPrice = wall.priceTick * instrumentInfo.pips;
            if (currentPrice > wallRealPrice && wallTracker.isConsumed(wall)) {
                BreakoutSignal signal = new BreakoutSignal(alias, wallRealPrice);
                // sharedServer.broadcastSignal(signal.toJson());
                PluginLog.info("[Rong] BREAKOUT signal: " + signal.toJson());
                wallTracker.removeWall(wall.priceTick);
            }
        }
    }

    private void handleWallChangeEvent(OrderWallChangeEvent event) {
        if (wallLabelStore != null) {
            wallLabelStore.markBestMatchDisplayed(
                    event.getInstrumentAlias(),
                    event.isBid(),
                    event.getPriceTick(),
                    event.getEventTimeNs());
        }
        if (wallChangeStore != null) {
            wallChangeStore.addEvent(event);
        }
        playWallChangeSound(event);
    }

    private void playWallChangeSound(OrderWallChangeEvent event) {
        if (api == null || indicatorConfig == null
                || !indicatorConfig.isEnabled(IndicatorConfig.ORDER_WALL_CHANGE_SOUND)) {
            return;
        }
        try {
            api.sendUserMessage(new Layer1ApiSoundAlertMessage(
                    WALL_CHANGE_SOUND,
                    event.getLogMessage(),
                    1,
                    Duration.ZERO,
                    null,
                    RongPlugin.class,
                    event.getId()));
        } catch (RuntimeException e) {
            PluginLog.error("[WallChange] Failed to play alert sound", e);
        }
    }

    private void refreshWallLabelsIfNeeded(boolean force) {
        if (!wallLabelsDirty || wallLabelPainter == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastWallLabelRefreshMs < WALL_LABEL_REFRESH_MS) {
            return;
        }
        wallLabelPainter.refreshInstrument(alias);
        wallLabelsDirty = false;
        lastWallLabelRefreshMs = now;
    }

    private long getEventTimeNs() {
        return lastTimestampNs > 0 ? lastTimestampNs : System.currentTimeMillis() * 1_000_000L;
    }

    private synchronized void startReplayExport() {
        if (replayExportSession != null) {
            return;
        }
        replayExportSession = BookmapReplayExportSession.open(
                EXPORT_RUN_ID,
                "Rong",
                rawAlias,
                alias,
                instrumentInfo,
                lastTimestampNs > 0 ? lastTimestampNs : initialTimestampNs);
        if (replayExportSession != null) {
            PluginLog.info("[Rong] Replay export enabled for " + alias);
        }
    }

    private synchronized void stopReplayExport() {
        if (replayExportSession == null) {
            return;
        }
        replayExportSession.stop();
        replayExportSession = null;
        PluginLog.info("[Rong] Replay export disabled for " + alias);
    }
}
