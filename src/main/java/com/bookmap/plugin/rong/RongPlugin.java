package com.bookmap.plugin.rong;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.bookmap.plugin.rong.exporter.BookmapReplayExportSession;
import com.bookmap.plugin.rong.executions.FilledExecutionManager;
import com.bookmap.plugin.rong.executions.FilledExecutionPainter;
import com.bookmap.plugin.rong.executions.FilledExecutionStore;
import com.bookmap.plugin.rong.orderwall.OrderWallChangeEvent;
import com.bookmap.plugin.rong.orderwall.OrderWallChangePainter;
import com.bookmap.plugin.rong.orderwall.OrderWallChangeSound;
import com.bookmap.plugin.rong.orderwall.OrderWallChangeStore;
import com.bookmap.plugin.rong.orderwall.OrderWallChangeTracker;
import com.bookmap.plugin.rong.orderwall.OrderWallLabelPainter;
import com.bookmap.plugin.rong.orderwall.OrderWallLabelStore;
import com.bookmap.plugin.rong.orderwall.OrderWallLabelTracker;
import com.bookmap.plugin.rong.orderwall.OrderWallTracker;
import com.bookmap.plugin.rong.patterns.BookmapPatternEngine;
import com.bookmap.plugin.rong.patterns.BookmapPatternSignal;
import com.bookmap.plugin.rong.patterns.PatternSignalLogger;
import com.bookmap.plugin.rong.patterns.PatternSignalPainter;
import com.bookmap.plugin.rong.patterns.PatternSignalStore;
import com.bookmap.plugin.rong.pricelines.ChartClickHandler;
import com.bookmap.plugin.rong.pricelines.PriceLinePainter;
import com.bookmap.plugin.rong.pricelines.PriceLineStore;
import com.bookmap.plugin.rong.pricelines.PriceZonePainter;
import com.bookmap.plugin.rong.pricelines.PriceZoneStore;
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
        CustomSettingsPanelProvider, ReplayExportConfig.ChangeListener,
        IndicatorConfig.ChangeListener {

    private static final int WS_PORT = 8765;
    private static final DateTimeFormatter EXPORT_RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final String EXPORT_RUN_ID = LocalDateTime.now().format(EXPORT_RUN_ID_FORMAT);
    private static final int WALL_THRESHOLD = 500_000;
    private static final double WALL_CONSUMED_RATIO = 0.10;
    private static final double ORDERBOOK_PERCENTILE = 97;
    private static final int ORDERBOOK_INTERVAL_MS = 1000;
    private static final int WALL_LABEL_MIN_SIZE = 5_000;
    private static final int WALL_LABEL_RETAIN_TICKS = 2_000;
    private static final int WALL_LABEL_REFRESH_MS = 200;
    private static final double WALL_CHANGE_REMAINING_RATIO = 0.50;
    private static final long WALL_CHANGE_DECISION_DELAY_MS = 500;
    private static final byte[] WALL_CHANGE_SOUND = OrderWallChangeSound.createAlertSound();

    // Shared WebSocket server across all symbol instances
    private static SignalWebSocketServer sharedServer;
    private static int instanceCount = 0;
    private static ChartClickHandler chartClickHandler;
    private static PriceLineStore priceLineStore;
    private static PriceLinePainter priceLinePainter;
    private static PriceZoneStore priceZoneStore;
    private static PriceZonePainter priceZonePainter;
    private static OrderWallLabelStore wallLabelStore;
    private static OrderWallLabelPainter wallLabelPainter;
    private static OrderWallChangeStore wallChangeStore;
    private static OrderWallChangePainter wallChangePainter;
    private static IndicatorConfig indicatorConfig;
    private static WallThresholdConfig wallThresholdConfig;
    private static ReplayExportConfig replayExportConfig;
    private static KeyLevelManager keyLevelManager;
    private static KeyZoneManager keyZoneManager;
    private static MarketLevelManager marketLevelManager;
    private static ExitOrderManager exitOrderManager;
    private static PendingEntryOrderManager pendingEntryOrderManager;
    private static FilledExecutionStore filledExecutionStore;
    private static FilledExecutionPainter filledExecutionPainter;
    private static FilledExecutionManager filledExecutionManager;
    private static PatternSignalStore patternSignalStore;
    private static PatternSignalPainter patternSignalPainter;
    private static PatternSignalLogger patternSignalLogger;

    private String rawAlias;
    private String alias;
    private Api api;
    private OrderWallTracker wallTracker;
    private InstrumentInfo instrumentInfo;
    private OrderBookState orderBook;
    private OrderWallLabelTracker wallLabelTracker;
    private OrderWallChangeTracker wallChangeTracker;
    private BookmapPatternEngine patternEngine;
    private volatile boolean patternAutomationEnabled;
    private volatile boolean patternSnapshotComplete;
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
            if (indicatorConfig == null) {
                indicatorConfig = new IndicatorConfig();
            }
            if (wallThresholdConfig == null) {
                wallThresholdConfig = new WallThresholdConfig();
            }
            if (replayExportConfig == null) {
                replayExportConfig = new ReplayExportConfig();
            }
            if (chartClickHandler == null) {
                chartClickHandler = new ChartClickHandler(sharedServer, indicatorConfig);
            }
            if (priceLineStore == null) {
                priceLineStore = new PriceLineStore();
                priceLinePainter = new PriceLinePainter(priceLineStore);
                priceZoneStore = new PriceZoneStore();
                priceZonePainter = new PriceZonePainter(priceZoneStore);
                wallLabelStore = new OrderWallLabelStore();
                wallChangeStore = new OrderWallChangeStore();
                wallLabelPainter = new OrderWallLabelPainter(wallLabelStore, indicatorConfig, wallChangeStore);
                wallChangePainter = new OrderWallChangePainter(wallChangeStore, indicatorConfig);
                patternSignalStore = new PatternSignalStore();
                patternSignalPainter = new PatternSignalPainter(patternSignalStore, indicatorConfig);
                patternSignalLogger = new PatternSignalLogger();
                keyLevelManager = new KeyLevelManager(priceLineStore);
                sharedServer.registerKeyLevelConfigListener(keyLevelManager);
                keyZoneManager = new KeyZoneManager(priceZoneStore);
                sharedServer.registerKeyZoneConfigListener(keyZoneManager);
                marketLevelManager = new MarketLevelManager(priceLineStore, indicatorConfig);
                sharedServer.registerMarketLevelConfigListener(marketLevelManager);
                exitOrderManager = new ExitOrderManager(priceLineStore);
                sharedServer.registerExitOrderPairsConfigListener(exitOrderManager);
                pendingEntryOrderManager = new PendingEntryOrderManager(priceLineStore);
                sharedServer.registerAccountStateListener(pendingEntryOrderManager);
                filledExecutionStore = new FilledExecutionStore();
                filledExecutionPainter = new FilledExecutionPainter(filledExecutionStore, indicatorConfig);
                filledExecutionManager = new FilledExecutionManager(filledExecutionStore);
                sharedServer.registerAccountStateListener(filledExecutionManager);
            }
            instanceCount++;
        }
        replayExportConfig.addChangeListener(this);
        this.wallLabelTracker = new OrderWallLabelTracker(
                cleanAlias, info.pips, wallLabelStore, WALL_LABEL_MIN_SIZE, WALL_LABEL_RETAIN_TICKS,
                this::handleWallLabelTrackerChange);
        this.wallChangeTracker = new OrderWallChangeTracker(
                cleanAlias,
                info.pips,
                wallThresholdConfig::getThresholdFloor,
                ORDERBOOK_PERCENTILE,
                WALL_CHANGE_REMAINING_RATIO,
                WALL_CHANGE_DECISION_DELAY_MS,
                this::handleWallChangeEvent,
                this::isWallBreakAlertEnabled);
        this.patternEngine = new BookmapPatternEngine(
                cleanAlias,
                info.pips,
                wallThresholdConfig::getThresholdFloor,
                ORDERBOOK_PERCENTILE,
                orderBook,
                priceLineStore,
                priceZoneStore,
                patternType -> indicatorConfig != null
                        && indicatorConfig.isEnabled(IndicatorConfig.BOOKMAP_PATTERN_SIGNALS)
                        && sharedServer != null
                        && sharedServer.hasEnabledPatternTradebook(cleanAlias, patternType),
                this::handlePatternSignal);
        this.patternAutomationEnabled = indicatorConfig.isEnabled(
                IndicatorConfig.BOOKMAP_PATTERN_SIGNALS);
        indicatorConfig.addChangeListener(this);
        sharedServer.registerSymbol(cleanAlias, orderBook, info.pips);
        chartClickHandler.registerSymbol(cleanAlias, info.pips);
        priceZonePainter.registerInstrument(cleanAlias);
        priceLinePainter.registerInstrument(cleanAlias);
        wallLabelPainter.registerInstrument(cleanAlias);
        wallChangePainter.registerInstrument(cleanAlias);
        patternSignalPainter.registerInstrument(cleanAlias);
        filledExecutionPainter.registerInstrument(cleanAlias);

        // Register ScreenSpacePainter to receive chart coordinate mappings
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, "clickHandler_" + cleanAlias)
                .setScreenSpacePainterFactory(chartClickHandler)
                .setIsAdd(true)
                .build());

        // Register ScreenSpacePainter for drawing price zones
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, PriceZonePainter.PAINTER_NAME_PREFIX + cleanAlias)
                .setScreenSpacePainterFactory(priceZonePainter)
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
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, PatternSignalPainter.PAINTER_NAME_PREFIX + cleanAlias)
                .setScreenSpacePainterFactory(patternSignalPainter)
                .setIsAdd(true)
                .build());
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, FilledExecutionPainter.PAINTER_NAME_PREFIX + cleanAlias)
                .setScreenSpacePainterFactory(filledExecutionPainter)
                .setIsAdd(true)
                .build());

        // Draw predefined key price levels for this instrument
        if (keyLevelManager != null) {
            keyLevelManager.onInstrumentInitialized(cleanAlias, info.pips);
        }
        if (keyZoneManager != null) {
            keyZoneManager.onInstrumentInitialized(cleanAlias, info.pips);
        }
        if (marketLevelManager != null) {
            marketLevelManager.onInstrumentInitialized(cleanAlias, info.pips);
        }
        if (exitOrderManager != null) {
            exitOrderManager.onInstrumentInitialized(cleanAlias, info.pips);
        }
        if (pendingEntryOrderManager != null) {
            pendingEntryOrderManager.onInstrumentInitialized(cleanAlias, info.pips);
        }
        if (filledExecutionManager != null) {
            filledExecutionManager.onInstrumentInitialized(cleanAlias, info.pips);
        }

        tradeButtonWindow = new TradeButtonWindow(
                cleanAlias, sharedServer, wallThresholdConfig::getThresholdFloor);

        if (replayExportConfig.isEnabled()) {
            startReplayExport();
        }

        PluginLog.info("[Rong] Plugin initialized for " + cleanAlias);
    }

    @Override
    public void stop() {
        if (indicatorConfig != null) {
            indicatorConfig.removeChangeListener(this);
        }
        if (replayExportConfig != null) {
            replayExportConfig.removeChangeListener(this);
        }
        stopReplayExport();
        if (wallLabelTracker != null) {
            wallLabelTracker.shutdown();
            wallLabelTracker = null;
        }
        if (wallChangeTracker != null) {
            wallChangeTracker.shutdown();
            wallChangeTracker = null;
        }
        if (patternEngine != null) {
            patternEngine.shutdown();
            patternEngine = null;
        }
        patternAutomationEnabled = false;
        patternSnapshotComplete = false;
        if (tradeButtonWindow != null) {
            tradeButtonWindow.dispose();
            tradeButtonWindow = null;
        }
        if (chartClickHandler != null) {
            chartClickHandler.unregisterSymbol(alias);
        }
        if (priceZonePainter != null) {
            priceZonePainter.unregisterInstrument(alias);
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
        if (patternSignalPainter != null) {
            patternSignalPainter.unregisterInstrument(alias);
        }
        if (filledExecutionPainter != null) {
            filledExecutionPainter.unregisterInstrument(alias);
        }
        // Unregister ScreenSpacePainters
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, "clickHandler_" + alias)
                .setScreenSpacePainterFactory(chartClickHandler)
                .setIsAdd(false)
                .build());
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, PriceZonePainter.PAINTER_NAME_PREFIX + alias)
                .setScreenSpacePainterFactory(priceZonePainter)
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
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, PatternSignalPainter.PAINTER_NAME_PREFIX + alias)
                .setScreenSpacePainterFactory(patternSignalPainter)
                .setIsAdd(false)
                .build());
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, FilledExecutionPainter.PAINTER_NAME_PREFIX + alias)
                .setScreenSpacePainterFactory(filledExecutionPainter)
                .setIsAdd(false)
                .build());

        if (keyLevelManager != null) {
            keyLevelManager.onInstrumentStopped(alias);
        }
        if (keyZoneManager != null) {
            keyZoneManager.onInstrumentStopped(alias);
        }
        if (marketLevelManager != null) {
            marketLevelManager.onInstrumentStopped(alias);
        }
        if (exitOrderManager != null) {
            exitOrderManager.onInstrumentStopped(alias);
        }
        if (pendingEntryOrderManager != null) {
            pendingEntryOrderManager.onInstrumentStopped(alias);
        }
        if (filledExecutionManager != null) {
            filledExecutionManager.onInstrumentStopped(alias);
        }
        if (priceLineStore != null) {
            priceLineStore.clearAll(alias);
        }
        if (priceZoneStore != null) {
            priceZoneStore.clearAll(alias);
        }
        if (wallLabelStore != null) {
            wallLabelStore.clearAll(alias);
        }
        if (wallChangeStore != null) {
            wallChangeStore.clearAll(alias);
        }
        if (patternSignalStore != null) {
            patternSignalStore.clearAll(alias);
        }
        if (filledExecutionStore != null) {
            filledExecutionStore.clearAll(alias);
        }
        if (sharedServer != null) {
            sharedServer.unregisterSymbol(alias);
        }
        synchronized (RongPlugin.class) {
            instanceCount--;
            if (instanceCount <= 0 && sharedServer != null) {
                ChartClickHandler.removeAwtListener();
                if (keyLevelManager != null) {
                    sharedServer.unregisterKeyLevelConfigListener(keyLevelManager);
                    keyLevelManager.shutdown();
                    keyLevelManager = null;
                }
                if (keyZoneManager != null) {
                    sharedServer.unregisterKeyZoneConfigListener(keyZoneManager);
                    keyZoneManager.shutdown();
                    keyZoneManager = null;
                }
                if (marketLevelManager != null) {
                    sharedServer.unregisterMarketLevelConfigListener(marketLevelManager);
                    marketLevelManager.shutdown();
                    marketLevelManager = null;
                }
                if (exitOrderManager != null) {
                    sharedServer.unregisterExitOrderPairsConfigListener(exitOrderManager);
                    exitOrderManager.shutdown();
                    exitOrderManager = null;
                }
                if (pendingEntryOrderManager != null) {
                    sharedServer.unregisterAccountStateListener(pendingEntryOrderManager);
                    pendingEntryOrderManager.shutdown();
                    pendingEntryOrderManager = null;
                }
                if (filledExecutionManager != null) {
                    sharedServer.unregisterAccountStateListener(filledExecutionManager);
                    filledExecutionManager.shutdown();
                    filledExecutionManager = null;
                }
                if (wallLabelPainter != null) {
                    wallLabelPainter.shutdown();
                }
                if (wallChangePainter != null) {
                    wallChangePainter.shutdown();
                }
                if (patternSignalPainter != null) {
                    patternSignalPainter.shutdown();
                }
                if (patternSignalLogger != null) {
                    patternSignalLogger.close();
                }
                if (filledExecutionPainter != null) {
                    filledExecutionPainter.shutdown();
                }
                ActionLogWindow.dispose();
                sharedServer.shutdown();
                sharedServer = null;
                chartClickHandler = null;
                priceLineStore = null;
                priceLinePainter = null;
                priceZoneStore = null;
                priceZonePainter = null;
                wallLabelStore = null;
                wallLabelPainter = null;
                wallChangeStore = null;
                wallChangePainter = null;
                patternSignalStore = null;
                patternSignalPainter = null;
                patternSignalLogger = null;
                filledExecutionStore = null;
                filledExecutionPainter = null;
                indicatorConfig = null;
                wallThresholdConfig = null;
                replayExportConfig = null;
                pendingEntryOrderManager = null;
                instanceCount = 0;
                PluginLog.info("[Rong] Shared WebSocket server shut down");
            }
        }
        PluginLog.info("[Rong] Plugin stopped for " + alias);
    }

    @Override
    public StrategyPanel[] getCustomSettingsPanels() {
        synchronized (RongPlugin.class) {
            if (indicatorConfig == null) {
                indicatorConfig = new IndicatorConfig();
            }
            if (wallThresholdConfig == null) {
                wallThresholdConfig = new WallThresholdConfig();
            }
            if (replayExportConfig == null) {
                replayExportConfig = new ReplayExportConfig();
            }
        }
        return new StrategyPanel[] {
            new IndicatorSettingsPanel(indicatorConfig, wallThresholdConfig),
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
        if (shouldRunPatternAutomation()) {
            patternEngine.onDepth(isBid, price, size, getEventTimeNs());
        }
        wallTracker.updateLevel(isBid, price, size);
        if (wallLabelTracker != null && wallLabelTracker.onDepth(isBid, price, size, getEventTimeNs())) {
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
        int priceTick = (int) Math.round(price);

        if (shouldRunPatternAutomation()) {
            patternEngine.onTrade(price, size, tradeInfo, getEventTimeNs());
        }

        if (sharedServer != null) {
            sharedServer.updateRegularSessionHighLow(alias, realPrice, getEventTimeNs());
        }
        checkBreakout(realPrice);
        wallTracker.cleanup(priceTick);
        if (wallLabelTracker != null && wallLabelTracker.cleanup(priceTick)) {
            wallLabelsDirty = true;
        }
        refreshWallLabelsIfNeeded(true);

    }

    @Override
    public void onTimestamp(long timestampNs) {
        this.lastTimestampNs = timestampNs;
        if (shouldRunPatternAutomation()) {
            patternEngine.onTimestamp(timestampNs);
        }
        if (wallLabelTracker != null && wallLabelTracker.onTimestamp(timestampNs)) {
            wallLabelsDirty = true;
            refreshWallLabelsIfNeeded(true);
        }
        BookmapReplayExportSession exportSession = replayExportSession;
        if (exportSession != null) {
            exportSession.onTimestamp(timestampNs);
        }
    }

    @Override
    public void onBbo(int bidPrice, int bidSize, int askPrice, int askSize) {
        if (shouldRunPatternAutomation()) {
            patternEngine.onBbo(bidPrice, bidSize, askPrice, askSize, getEventTimeNs());
        }
        BookmapReplayExportSession exportSession = replayExportSession;
        if (exportSession != null) {
            exportSession.onBbo(bidPrice, bidSize, askPrice, askSize);
        }
    }

    @Override
    public void onSnapshotEnd() {
        patternSnapshotComplete = true;
        BookmapReplayExportSession exportSession = replayExportSession;
        if (exportSession != null) {
            exportSession.onSnapshotEnd();
        }
        if (wallChangeTracker != null) {
            wallChangeTracker.markReady();
        }
        if (shouldRunPatternAutomation()) {
            patternEngine.markReady();
        }
    }

    @Override
    public void onRealtimeStart() {
        patternSnapshotComplete = true;
        BookmapReplayExportSession exportSession = replayExportSession;
        if (exportSession != null) {
            exportSession.onRealtimeStart();
        }
        if (wallChangeTracker != null) {
            wallChangeTracker.markReady();
        }
        if (shouldRunPatternAutomation()) {
            patternEngine.markReady();
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

    @Override
    public void onIndicatorConfigChanged(String indicatorKey, boolean enabled) {
        if (!IndicatorConfig.BOOKMAP_PATTERN_SIGNALS.equals(indicatorKey)) return;
        patternAutomationEnabled = enabled;
        BookmapPatternEngine engine = patternEngine;
        if (engine != null) {
            engine.resetForFeatureToggle();
            if (enabled && patternSnapshotComplete) {
                engine.markReady();
            }
        }
        if (!enabled && patternSignalStore != null && alias != null) {
            patternSignalStore.clearAll(alias);
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

    private boolean isWallBreakAlertEnabled(boolean bidWall) {
        if (indicatorConfig == null
                || !indicatorConfig.isEnabled(IndicatorConfig.ORDER_WALL_BREAKOUT_SIGNALS)) {
            return false;
        }
        SignalWebSocketServer server = sharedServer;
        return server != null && server.hasEnabledWallBreakTradeButton(alias, bidWall);
    }

    private boolean shouldRunPatternAutomation() {
        return patternAutomationEnabled && patternEngine != null;
    }

    private void handlePatternSignal(BookmapPatternSignal signal) {
        PatternSignalStore store = patternSignalStore;
        if (store != null) store.addOrUpdate(signal);
        PatternSignalLogger logger = patternSignalLogger;
        if (logger != null) logger.append(signal);
        PluginLog.info("[PatternSignal] " + signal.toJson());
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

    private void handleWallLabelTrackerChange() {
        wallLabelsDirty = true;
        refreshWallLabelsIfNeeded(true);
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
