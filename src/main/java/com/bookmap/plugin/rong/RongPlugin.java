package com.bookmap.plugin.rong;

import java.awt.Color;
import java.time.Duration;
import com.bookmap.plugin.rong.patterns.PatternObservationConfig;
import com.bookmap.plugin.rong.signal.ResetReason;
import com.bookmap.plugin.rong.patterns.PatternEvent;

import com.bookmap.plugin.rong.executions.FilledExecutionManager;
import com.bookmap.plugin.rong.executions.FilledExecutionPainter;
import com.bookmap.plugin.rong.executions.FilledExecutionStore;
import com.bookmap.plugin.rong.miniviteapp.runtime.LocalCredentials;
import com.bookmap.plugin.rong.orderwall.OrderWallChangeEvent;
import com.bookmap.plugin.rong.orderwall.OrderWallChangePainter;
import com.bookmap.plugin.rong.orderwall.OrderWallChangeSound;
import com.bookmap.plugin.rong.orderwall.OrderWallChangeStore;
import com.bookmap.plugin.rong.orderwall.OrderWallChangeTracker;
import com.bookmap.plugin.rong.orderwall.OrderWallLabelPainter;
import com.bookmap.plugin.rong.orderwall.OrderWallLabelStore;
import com.bookmap.plugin.rong.orderwall.OrderWallLabelTracker;
import com.bookmap.plugin.rong.pricelines.ChartHoverHotkeyHandler;
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
import velox.api.layer1.annotations.UnrestrictedData;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.messages.Layer1ApiSoundAlertMessage;
import velox.api.layer1.messages.indicators.AliasFilter;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator.GraphType;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyScreenSpacePainter;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.BackfilledDataListener;
import velox.api.layer1.simplified.BboListener;
import velox.api.layer1.simplified.CustomModuleAdapter;
import velox.api.layer1.simplified.DepthDataListener;
import velox.api.layer1.simplified.HistoricalModeListener;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.IndicatorModifiable;
import velox.api.layer1.simplified.SnapshotEndListener;
import velox.api.layer1.simplified.TimeListener;
import velox.api.layer1.simplified.TradeDataListener;
import velox.gui.StrategyPanel;

@Layer1SimpleAttachable
@Layer1StrategyName(PluginVersion.NAME)
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
@UnrestrictedData
public class RongPlugin implements CustomModuleAdapter,
        DepthDataListener, TradeDataListener, TimeListener,
        SnapshotEndListener, BboListener, HistoricalModeListener, BackfilledDataListener,
        CustomSettingsPanelProvider, IndicatorConfig.ChangeListener {

    private static final int WS_PORT = 8765;
    private static final double ORDERBOOK_PERCENTILE = 97;
    private static final int DISPLAY_LIQUIDITY_MIN_SIZE = 5_000;
    private static final int WALL_LABEL_RETAIN_TICKS = 2_000;
    private static final int WALL_LABEL_REFRESH_MS = 200;
    private static final double WALL_CHANGE_REMAINING_RATIO = 0.50;
    private static final long WALL_CHANGE_DECISION_DELAY_MS = 500;
    private static final byte[] WALL_CHANGE_SOUND = OrderWallChangeSound.createAlertSound();
    private static final Color VWAP_COLOR = new Color(171, 71, 188);
    private static final Color VWAP_HIDDEN_COLOR = new Color(171, 71, 188, 0);

    // Shared WebSocket server across all symbol instances
    private static SignalWebSocketServer sharedServer;
    private static NativeTradingAdapter nativeTrading;
    private static final NativeConnectionStatus nativeConnectionStatus = new NativeConnectionStatus();
    private static final java.util.Map<String, Api> nativeApis = new java.util.LinkedHashMap<>();
    private static int instanceCount = 0;
    private static ChartHoverHotkeyHandler chartHoverHotkeyHandler;
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
    private static KeyLevelManager keyLevelManager;
    private static KeyZoneManager keyZoneManager;
    private static MarketLevelManager marketLevelManager;
    private static ExitOrderManager exitOrderManager;
    private static PendingEntryOrderManager pendingEntryOrderManager;
    private static FilledExecutionStore filledExecutionStore;
    private static FilledExecutionPainter filledExecutionPainter;
    private static FilledExecutionManager filledExecutionManager;
    private static PatternObservationConfig patternEventConfig;

    private String rawAlias;
    private volatile boolean initialized;
    private String alias;
    private Api api;
    private InstrumentInfo instrumentInfo;
    private OrderBookState orderBook;
    private OrderWallLabelTracker wallLabelTracker;
    private OrderWallChangeTracker wallChangeTracker;
    private com.bookmap.plugin.rong.patterns.PatternEventPipeline patternEvents;
    private com.bookmap.plugin.rong.patterns.FalseBreakoutMonitor falseBreakoutMonitor;
    private com.bookmap.plugin.rong.patterns.FalseBreakoutPainter falseBreakoutPainter;
    private com.bookmap.plugin.rong.patterns.ReclaimSecondBidMonitor reclaimSecondBidMonitor;
    private com.bookmap.plugin.rong.patterns.ReclaimSecondBidPainter reclaimSecondBidPainter;
    private com.bookmap.plugin.rong.patterns.BidBreakdownMonitor bidBreakdownMonitor;
    private com.bookmap.plugin.rong.patterns.BidBreakdownPainter bidBreakdownPainter;
    private SignalWebSocketServer.PatternMonitorConfigListener patternMonitorConfigListener;
    private PatternObservationConfig patternRules;
    private volatile boolean patternEventSnapshotComplete;
    private volatile boolean patternMonitorSnapshotComplete;
    // HistoricalModeListener delivers stored callbacks before onRealtimeStart.
    // Keep their liquidity evidence separate from live pattern snapshot readiness.
    private boolean sessionHistoryLoading = true;
    private volatile boolean patternEventSeedFromSharedBook = true;
    private final Object patternEventLock = new Object();
    private final com.bookmap.plugin.rong.patterns.CairoObservationConfig observationConfig = com.bookmap.plugin.rong.patterns.CairoObservationConfig.load();
    private boolean wallLabelsDirty;
    private long lastWallLabelRefreshMs;
    private volatile long lastTimestampNs;
    private PremarketSizeThreshold premarketSizeThreshold;
    private volatile double wallChangeStockPrice = Double.NaN;
    private TradeButtonWindow tradeButtonWindow;
    private volatile ReplayTradeWindow replayTradeWindow;
    private BookmapRunMode runMode = BookmapRunMode.UNKNOWN;
    private VwapTracker vwapTracker;
    private SignalWebSocketServer.VwapUpdateListener vwapUpdateListener;
    private IndicatorModifiable vwapIndicator;

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        runMode = BookmapRunMode.detect(observationConfig.sourceMode);
        if (!LocalCredentials.fileExists() && !observationConfig.observerOnly && runMode != BookmapRunMode.REPLAY) return;
        boolean nativeExecution = runMode == BookmapRunMode.LIVE && !observationConfig.observerOnly;
        this.rawAlias = alias;
        patternMonitorSnapshotComplete = false;
        sessionHistoryLoading = true;
        String cleanAlias = SymbolUtils.cleanSymbol(alias);
        // Bookmap may not have complete instrument metadata during attachment.
        // Do not register indicators or start shared services for an unusable chart.
        if (cleanAlias.isEmpty() || api == null || info == null
                || !Double.isFinite(info.pips) || info.pips <= 0) return;
        this.alias = cleanAlias;
        this.api = api;
        this.instrumentInfo = info;
        this.lastTimestampNs = initialState != null ? initialState.getCurrentTime() : 0L;
        this.orderBook = new OrderBookState();
        this.vwapTracker = new VwapTracker(cleanAlias);
        this.vwapUpdateListener = update -> {
            if (vwapTracker != null) {
                vwapTracker.applyUpdate(update);
            }
        };

        synchronized (RongPlugin.class) {
            if (sharedServer == null) {
                ActionLogWindow.showWindow(runMode.banner);
                PluginLog.start();
                sharedServer = new SignalWebSocketServer(WS_PORT, ORDERBOOK_PERCENTILE);
                sharedServer.start();
            }
            if (indicatorConfig == null) {
                indicatorConfig = new IndicatorConfig();
            }
            if (wallThresholdConfig == null) {
                wallThresholdConfig = new WallThresholdConfig();
            }
            sharedServer.setWallThresholdFloor(wallThresholdConfig::getThresholdFloor);
            if (chartHoverHotkeyHandler == null) {
                chartHoverHotkeyHandler = new ChartHoverHotkeyHandler(
                        sharedServer, indicatorConfig);
            }
            if (priceLineStore == null) {
                priceLineStore = new PriceLineStore();
                sharedServer.setEntryBidReclaimStore(priceLineStore);
                priceLinePainter = new PriceLinePainter(priceLineStore);
                priceZoneStore = new PriceZoneStore();
                priceZonePainter = new PriceZonePainter(priceZoneStore);
                wallLabelStore = new OrderWallLabelStore();
                wallChangeStore = new OrderWallChangeStore();
                wallLabelPainter = new OrderWallLabelPainter(wallLabelStore, indicatorConfig, wallChangeStore);
                wallChangePainter = new OrderWallChangePainter(wallChangeStore, indicatorConfig);
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
            if (nativeExecution) {
            nativeApis.put(cleanAlias, api);
            if (nativeTrading == null) { nativeTrading = new NativeTradingAdapter(sharedServer, indicatorConfig, nativeConnectionStatus); nativeTrading.attach(cleanAlias, api); nativeTrading.start(); }
            else nativeTrading.attach(cleanAlias, api);
            }
            instanceCount++;
            initialized = true;
        }
        this.vwapIndicator = api.registerIndicatorModifiable("VWAP", GraphType.PRIMARY);
        this.vwapIndicator.setWidth(2);
        updateVwapIndicatorVisibility();
        premarketSizeThreshold = new PremarketSizeThreshold(cleanAlias,
                () -> lastTimestampNs / 1_000_000L,
                (symbol, from, to) -> {
                    NativeTradingAdapter trading = nativeTrading;
                    if (trading == null) throw new IllegalStateException("Schwab runtime unavailable");
                    return trading.premarketMinuteBars(symbol, from, to);
                });
        sharedServer.registerSizeThreshold(cleanAlias, premarketSizeThreshold);
        premarketSizeThreshold.start();
        this.wallLabelTracker = new OrderWallLabelTracker(
                cleanAlias, info.pips, wallLabelStore, () -> DISPLAY_LIQUIDITY_MIN_SIZE,
                WALL_LABEL_RETAIN_TICKS,
                this::handleWallLabelTrackerChange);
        this.wallChangeTracker = new OrderWallChangeTracker(
                cleanAlias,
                info.pips,
                this::getEffectiveWallThreshold,
                0,
                WALL_CHANGE_REMAINING_RATIO,
                WALL_CHANGE_DECISION_DELAY_MS,
                this::handleWallChangeEvent,
                this::getWallChangeDayHigh,
                this::getWallChangeDayLow,
                () -> wallChangeStockPrice);
        initializePatternEvents(info.pips);
        falseBreakoutMonitor = new com.bookmap.plugin.rong.patterns.FalseBreakoutMonitor(this::getEffectiveWallThreshold);
        falseBreakoutPainter = new com.bookmap.plugin.rong.patterns.FalseBreakoutPainter(falseBreakoutMonitor);
        reclaimSecondBidMonitor = new com.bookmap.plugin.rong.patterns.ReclaimSecondBidMonitor(this::getEffectiveWallThreshold);
        reclaimSecondBidPainter = new com.bookmap.plugin.rong.patterns.ReclaimSecondBidPainter(reclaimSecondBidMonitor);
        bidBreakdownMonitor = new com.bookmap.plugin.rong.patterns.BidBreakdownMonitor(this::isEntryLiquidityReady);
        bidBreakdownPainter = new com.bookmap.plugin.rong.patterns.BidBreakdownPainter(bidBreakdownMonitor);
        patternMonitorConfigListener = (symbol, patterns) -> {
            if (!cleanAlias.equals(symbol)) return;
            falseBreakoutMonitor.setEnabled(patterns.contains(com.bookmap.plugin.rong.patterns.FalseBreakoutMonitor.PATTERN));
            falseBreakoutPainter.refresh();
            reclaimSecondBidMonitor.setEnabled((patterns.contains(com.bookmap.plugin.rong.patterns.ReclaimSecondBidMonitor.PATTERN) || patterns.contains("falseBreakdown")));
            reclaimSecondBidPainter.refresh();
            bidBreakdownMonitor.setEnabled(patterns.contains(com.bookmap.plugin.rong.patterns.BidBreakdownMonitor.PATTERN));
            bidBreakdownPainter.refresh();
        };
        sharedServer.registerPatternMonitorConfigListener(patternMonitorConfigListener);
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(RongPlugin.class,
                com.bookmap.plugin.rong.patterns.FalseBreakoutPainter.NAME_PREFIX + cleanAlias)
                .setScreenSpacePainterFactory(falseBreakoutPainter).setAliasFilter(exactAliasFilter(rawAlias)).setIsAdd(true).build());
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(RongPlugin.class,
                com.bookmap.plugin.rong.patterns.ReclaimSecondBidPainter.NAME_PREFIX + cleanAlias)
                .setScreenSpacePainterFactory(reclaimSecondBidPainter).setAliasFilter(exactAliasFilter(rawAlias)).setIsAdd(true).build());
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(RongPlugin.class,
                com.bookmap.plugin.rong.patterns.BidBreakdownPainter.NAME_PREFIX + cleanAlias)
                .setScreenSpacePainterFactory(bidBreakdownPainter).setAliasFilter(exactAliasFilter(rawAlias)).setIsAdd(true).build());

        indicatorConfig.addChangeListener(this);
        sharedServer.registerSymbol(cleanAlias, orderBook, info.pips);
        sharedServer.publishCairoChart(cleanAlias, runMode.dataMode);
        sharedServer.beginSessionHistory(cleanAlias);
        if (observationConfig.exportsPatterns(cleanAlias) && patternEvents != null) {
            sharedServer.registerPatterns(cleanAlias, new com.bookmap.plugin.rong.signal.CairoPatternFeed(
                    reconnect -> cairoPatternSnapshot(reconnect), observationConfig.captureEvidence));
        }
        sharedServer.registerVwapUpdateListener(cleanAlias, vwapUpdateListener);
        if (nativeExecution) chartHoverHotkeyHandler.registerSymbol(cleanAlias, info.pips);
        priceZonePainter.registerInstrument(cleanAlias);
        priceLinePainter.registerInstrument(cleanAlias);
        wallLabelPainter.registerInstrument(cleanAlias);
        wallChangePainter.registerInstrument(cleanAlias);
        filledExecutionPainter.registerInstrument(cleanAlias);

        // Register ScreenSpacePainter to receive chart coordinate mappings
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, ChartHoverHotkeyHandler.PAINTER_NAME_PREFIX + cleanAlias)
                .setScreenSpacePainterFactory(chartHoverHotkeyHandler)
                .setAliasFilter(exactAliasFilter(rawAlias))
                .setIsAdd(true)
                .build());

        // Register ScreenSpacePainter for drawing price zones
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, PriceZonePainter.PAINTER_NAME_PREFIX + cleanAlias)
                .setScreenSpacePainterFactory(priceZonePainter)
                .setAliasFilter(exactAliasFilter(rawAlias))
                .setIsAdd(true)
                .build());

        // Register ScreenSpacePainter for drawing price lines
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, "priceLines_" + cleanAlias)
                .setScreenSpacePainterFactory(priceLinePainter)
                .setAliasFilter(exactAliasFilter(rawAlias))
                .setIsAdd(true)
                .build());
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, OrderWallLabelPainter.PAINTER_NAME_PREFIX + cleanAlias)
                .setScreenSpacePainterFactory(wallLabelPainter)
                .setAliasFilter(exactAliasFilter(rawAlias))
                .setIsAdd(true)
                .build());
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, OrderWallChangePainter.PAINTER_NAME_PREFIX + cleanAlias)
                .setScreenSpacePainterFactory(wallChangePainter)
                .setAliasFilter(exactAliasFilter(rawAlias))
                .setIsAdd(true)
                .build());
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, FilledExecutionPainter.PAINTER_NAME_PREFIX + cleanAlias)
                .setScreenSpacePainterFactory(filledExecutionPainter)
                .setAliasFilter(exactAliasFilter(rawAlias))
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

        if (runMode == BookmapRunMode.REPLAY) {
            replayTradeWindow = new ReplayTradeWindow(cleanAlias, sharedServer::broadcastReplayEvent);
            replayTradeWindow.open();
        } else if (nativeExecution) {
            tradeButtonWindow = new TradeButtonWindow(
                    cleanAlias, sharedServer, wallThresholdConfig::getThresholdFloor, nativeConnectionStatus);
        }
    }

    static AliasFilter exactAliasFilter(String expectedAlias) {
        return new AliasFilter() {
            @Override
            public boolean isDisplayedForAlias(String candidateAlias) {
                return expectedAlias != null && expectedAlias.equals(candidateAlias);
            }
        };
    }

    /** Shared detector settings; each chart owns independent event observation state. */
    void initializePatternEvents(double pips) {
        synchronized (RongPlugin.class) {
            if (patternEventConfig == null) patternEventConfig = PatternObservationConfig.load();
            patternRules = patternEventConfig;
        }
        if (!patternRules.enabled || !patternRules.eligible(alias) || !Double.isFinite(pips) || pips <= 0) {
            if (!patternRules.valid) PluginLog.detail(alias, "PatternEvents", patternRules.error);
            return;
        }
        patternEvents = new com.bookmap.plugin.rong.patterns.PatternEventPipeline(alias, pips, patternRules,
                event -> PluginLog.detail(alias, "PatternEvents", "Event " + event.id + " revision=" + event.revision
                        + " type=" + event.type + " size=" + event.size + " price=" + event.price
                        + " occurrenceNs=" + event.eventTimeNs + " observedAtNs=" + event.observedAtNs),
                diagnostic -> PluginLog.detail(alias, "PatternEvents", diagnostic));
        patternEvents.setResetListener(reason -> {
            if (reason == ResetReason.COVERAGE_GAP || reason == ResetReason.REPLAY_SEEK) {
                patternEventSnapshotComplete = false; patternEventSeedFromSharedBook = false;
            }
        });
    }

    @Override
    public synchronized void stop() {
        if (!initialized) return;
        initialized = false;
        try {
            stopAttachment();
        } finally {
            releaseSharedResources();
        }
    }

    private void stopAttachment() {
        if (sharedServer != null && patternMonitorConfigListener != null) sharedServer.unregisterPatternMonitorConfigListener(patternMonitorConfigListener);
        if (bidBreakdownPainter != null) {
            cleanup("remove chart painter", () -> api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(RongPlugin.class,
                    com.bookmap.plugin.rong.patterns.BidBreakdownPainter.NAME_PREFIX + alias)
                    .setScreenSpacePainterFactory(bidBreakdownPainter).setIsAdd(false).build()));
            bidBreakdownPainter.shutdown();
        }
        if (falseBreakoutPainter != null) {
            cleanup("remove chart painter", () -> api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(RongPlugin.class,
                    com.bookmap.plugin.rong.patterns.FalseBreakoutPainter.NAME_PREFIX + alias)
                    .setScreenSpacePainterFactory(falseBreakoutPainter).setIsAdd(false).build()));
            falseBreakoutPainter.shutdown();
        }
        if (reclaimSecondBidPainter != null) {
            cleanup("remove chart painter", () -> api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(RongPlugin.class,
                    com.bookmap.plugin.rong.patterns.ReclaimSecondBidPainter.NAME_PREFIX + alias)
                    .setScreenSpacePainterFactory(reclaimSecondBidPainter).setIsAdd(false).build()));
            reclaimSecondBidPainter.shutdown();
        }
        synchronized (patternEventLock) {
            patternEventSnapshotComplete = false;
            if (patternEvents != null) patternEvents.reset(ResetReason.STOPPED);
            patternEvents = null; patternRules = null;
        }
        if (indicatorConfig != null) {
            indicatorConfig.removeChangeListener(this);
        }
        if (wallLabelTracker != null) {
            wallLabelTracker.shutdown();
            wallLabelTracker = null;
        }
        if (wallChangeTracker != null) {
            wallChangeTracker.shutdown();
            wallChangeTracker = null;
        }
        if (tradeButtonWindow != null) {
            tradeButtonWindow.dispose();
            tradeButtonWindow = null;
        }
        ReplayTradeWindow replay = replayTradeWindow;
        replayTradeWindow = null;
        if (replay != null) replay.dispose();
        if (sharedServer != null && vwapUpdateListener != null) {
            sharedServer.unregisterVwapUpdateListener(alias, vwapUpdateListener);
        }
        vwapUpdateListener = null;
        vwapTracker = null;
        vwapIndicator = null;
        if (chartHoverHotkeyHandler != null) {
            chartHoverHotkeyHandler.unregisterSymbol(alias);
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

        if (filledExecutionPainter != null) {
            filledExecutionPainter.unregisterInstrument(alias);
        }
        // Unregister ScreenSpacePainters
        cleanup("remove chart painter", () -> api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, ChartHoverHotkeyHandler.PAINTER_NAME_PREFIX + alias)
                .setScreenSpacePainterFactory(chartHoverHotkeyHandler)
                .setIsAdd(false)
                .build()));
        cleanup("remove chart painter", () -> api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, PriceZonePainter.PAINTER_NAME_PREFIX + alias)
                .setScreenSpacePainterFactory(priceZonePainter)
                .setIsAdd(false)
                .build()));
        cleanup("remove chart painter", () -> api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, "priceLines_" + alias)
                .setScreenSpacePainterFactory(priceLinePainter)
                .setIsAdd(false)
                .build()));
        cleanup("remove chart painter", () -> api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, OrderWallLabelPainter.PAINTER_NAME_PREFIX + alias)
                .setScreenSpacePainterFactory(wallLabelPainter)
                .setIsAdd(false)
                .build()));
        cleanup("remove chart painter", () -> api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, OrderWallChangePainter.PAINTER_NAME_PREFIX + alias)
                .setScreenSpacePainterFactory(wallChangePainter)
                .setIsAdd(false)
                .build()));
        cleanup("remove chart painter", () -> api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, FilledExecutionPainter.PAINTER_NAME_PREFIX + alias)
                .setScreenSpacePainterFactory(filledExecutionPainter)
                .setIsAdd(false)
                .build()));

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
        if (filledExecutionStore != null) {
            filledExecutionStore.clearAll(alias);
        }
        if (premarketSizeThreshold != null) premarketSizeThreshold.close();
        if (sharedServer != null) {
            sharedServer.unregisterSymbol(alias);
        }
    }

    private void releaseSharedResources() {
        synchronized (RongPlugin.class) {
            nativeApis.remove(alias);
            if (nativeTrading != null) cleanup("detach native trading", () -> nativeTrading.detach(alias));
            instanceCount--;
            if (instanceCount <= 0) {
                // Close the UI first so shutdown callbacks cannot recreate it.
                ActionLogWindow.dispose();
                cleanup("remove hotkey listener", ChartHoverHotkeyHandler::removeAwtListener);
                if (keyLevelManager != null) {
                    if (sharedServer != null) cleanup("unregister shared listener", () -> sharedServer.unregisterKeyLevelConfigListener(keyLevelManager));
                    cleanup("shut down shared resource", () -> keyLevelManager.shutdown());
                    keyLevelManager = null;
                }
                if (keyZoneManager != null) {
                    if (sharedServer != null) cleanup("unregister shared listener", () -> sharedServer.unregisterKeyZoneConfigListener(keyZoneManager));
                    cleanup("shut down shared resource", () -> keyZoneManager.shutdown());
                    keyZoneManager = null;
                }
                if (marketLevelManager != null) {
                    if (sharedServer != null) cleanup("unregister shared listener", () -> sharedServer.unregisterMarketLevelConfigListener(marketLevelManager));
                    cleanup("shut down shared resource", () -> marketLevelManager.shutdown());
                    marketLevelManager = null;
                }
                if (exitOrderManager != null) {
                    if (sharedServer != null) cleanup("unregister shared listener", () -> sharedServer.unregisterExitOrderPairsConfigListener(exitOrderManager));
                    cleanup("shut down shared resource", () -> exitOrderManager.shutdown());
                    exitOrderManager = null;
                }
                if (pendingEntryOrderManager != null) {
                    if (sharedServer != null) cleanup("unregister shared listener", () -> sharedServer.unregisterAccountStateListener(pendingEntryOrderManager));
                    cleanup("shut down shared resource", () -> pendingEntryOrderManager.shutdown());
                    pendingEntryOrderManager = null;
                }
                if (filledExecutionManager != null) {
                    if (sharedServer != null) cleanup("unregister shared listener", () -> sharedServer.unregisterAccountStateListener(filledExecutionManager));
                    cleanup("shut down shared resource", () -> filledExecutionManager.shutdown());
                    filledExecutionManager = null;
                }
                if (wallLabelPainter != null) {
                    cleanup("shut down shared resource", () -> wallLabelPainter.shutdown());
                }
                if (wallChangePainter != null) {
                    cleanup("shut down shared resource", () -> wallChangePainter.shutdown());
                }
                if (filledExecutionPainter != null) {
                    cleanup("shut down shared resource", () -> filledExecutionPainter.shutdown());
                }
                if (nativeTrading != null) { cleanup("close native trading", () -> nativeTrading.close()); nativeTrading = null; }
                nativeApis.clear();
                if (sharedServer != null) cleanup("stop WebSocket server", () -> sharedServer.shutdown());
                cleanup("stop logging", PluginLog::stop);
                sharedServer = null;
                chartHoverHotkeyHandler = null;
                priceLineStore = null;
                priceLinePainter = null;
                priceZoneStore = null;
                priceZonePainter = null;
                wallLabelStore = null;
                wallLabelPainter = null;
                wallChangeStore = null;
                wallChangePainter = null;
                filledExecutionStore = null;
                filledExecutionPainter = null;
                indicatorConfig = null;
                wallThresholdConfig = null;
                pendingEntryOrderManager = null;
                instanceCount = 0;
            }
            if (instanceCount <= 0) {
                patternEventConfig = null;
            }
        }
    }

    private static void cleanup(String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            PluginLog.detail("", "Shutdown", operation + " failed: " + error);
        }
    }

    @Override
    public StrategyPanel[] getCustomSettingsPanels() {
        BookmapRunMode mode = BookmapRunMode.detect(observationConfig.sourceMode);
        if (!LocalCredentials.fileExists() && !observationConfig.observerOnly && mode != BookmapRunMode.REPLAY) return new StrategyPanel[0];
        synchronized (RongPlugin.class) {
            if (indicatorConfig == null) {
                indicatorConfig = new IndicatorConfig();
            }
            if (wallThresholdConfig == null) {
                wallThresholdConfig = new WallThresholdConfig();
            }
        }
        return new StrategyPanel[] {
            new IndicatorSettingsPanel(indicatorConfig, wallThresholdConfig, nativeConnectionStatus,
                    observationConfig.observerOnly || mode != BookmapRunMode.LIVE)
        };
    }

    @Override
    public void onDepth(boolean isBid, int price, int size) {
        if (!initialized) return;
        // Discard absent/invalid price levels before any tracker or shared book sees them.
        // A zero size at a positive price is a valid level removal.
        if (price <= 0 || size < 0) return;
        long eventTimeNs = getEventTimeNs();
        PatternEvent.TimestampProvenance provenance = patternEventProvenance();
        if (wallChangeTracker != null) {
            wallChangeTracker.onDepth(isBid, price, size, eventTimeNs);
        }
        if (sharedServer != null) sharedServer.observeSessionDepth(alias, isBid, price, size, lastTimestampNs);
        synchronized (orderBook) {
            orderBook.update(isBid, price, size);
            if (sharedServer != null && runMode.dataMode.equals("live") && !sessionHistoryLoading) {
                sharedServer.markCairoLiveDepth(alias, lastTimestampNs);
            }
        }
        if (bidBreakdownMonitor != null) {
            bidBreakdownMonitor.onDepth(isBid, price, size, lastTimestampNs, patternMonitorSnapshotComplete);
            bidBreakdownPainter.refresh();
        }
        if (falseBreakoutMonitor != null) {
            falseBreakoutMonitor.onDepth(isBid, price, size, lastTimestampNs, patternMonitorSnapshotComplete);
            falseBreakoutPainter.refresh();
        }
        if (reclaimSecondBidMonitor != null) {
            reclaimSecondBidMonitor.onDepth(isBid, price, size, lastTimestampNs, patternMonitorSnapshotComplete);
            reclaimSecondBidPainter.refresh();
        }
        synchronized (patternEventLock) {
            if (shouldRunPatternEvents()) patternEvents.onDepth(isBid, price, size, eventTimeNs, provenance);
        }
        if (wallLabelTracker != null && wallLabelTracker.onDepth(isBid, price, size, eventTimeNs)) {
            wallLabelsDirty = true;
            refreshWallLabelsIfNeeded(false);
        }
    }

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        if (!initialized) return;
        if (!Double.isFinite(price) || price < 0.5 || price > Integer.MAX_VALUE
                || size <= 0) return;
        long eventTimeNs = getEventTimeNs();
        PatternEvent.TimestampProvenance provenance = patternEventProvenance();
        double realPrice = BookmapPriceNormalizer.toWirePrice(price, instrumentInfo.pips);
        if (sharedServer != null) sharedServer.publishCairoTrade(alias, runMode.dataMode, realPrice, lastTimestampNs);
        ReplayTradeWindow replay = replayTradeWindow;
        if (replay != null) replay.observeTrade(realPrice, lastTimestampNs);
        wallChangeStockPrice = realPrice;
        int priceTick = (int) Math.round(price);
        if (falseBreakoutMonitor != null) {
            falseBreakoutMonitor.onTrade(priceTick, size, tradeInfo == null ? null : tradeInfo.isBidAggressor,
                    lastTimestampNs, patternMonitorSnapshotComplete);
            falseBreakoutPainter.refresh();
        }
        if (reclaimSecondBidMonitor != null) {
            reclaimSecondBidMonitor.onTrade(priceTick, size, tradeInfo == null ? null : tradeInfo.isBidAggressor,
                    lastTimestampNs, patternMonitorSnapshotComplete);
            reclaimSecondBidPainter.refresh();
        }
        if (sharedServer != null) {
            sharedServer.observeSessionTime(alias, lastTimestampNs);
            if (sessionHistoryLoading) {
                sharedServer.observeSessionHistoricalTrade(alias, priceTick, size,
                        tradeInfo == null ? null : tradeInfo.isBidAggressor, lastTimestampNs);
            } else {
                sharedServer.observeSessionTrade(alias, priceTick, size, tradeInfo == null ? null : tradeInfo.isBidAggressor,
                        lastTimestampNs, patternMonitorSnapshotComplete);
            }
            sharedServer.updateRegularSessionHighLow(alias, realPrice, eventTimeNs);
        }
        if (bidBreakdownMonitor != null) {
            bidBreakdownMonitor.onTrade(priceTick, size, lastTimestampNs, patternMonitorSnapshotComplete);
            bidBreakdownPainter.refresh();
        }
        NativeTradingAdapter trading = nativeTrading;
        if (trading != null) trading.observeBookmapTrade(alias, realPrice, eventTimeNs);
        if (wallChangeTracker != null) {
            wallChangeTracker.onTrade(priceTick, size, tradeInfo);
        }
        flushPendingVwapPoints();

        synchronized (patternEventLock) {
            if (shouldRunPatternEvents()) patternEvents.onTrade(priceTick, size,
                    tradeInfo == null ? null : tradeInfo.isBidAggressor, eventTimeNs, provenance);
        }

        if (wallLabelTracker != null && wallLabelTracker.cleanup(priceTick)) {
            wallLabelsDirty = true;
        }
        refreshWallLabelsIfNeeded(true);
    }

    @Override
    public void onTimestamp(long timestampNs) {
        if (!initialized) return;
        ReplayTradeWindow replay = replayTradeWindow;
        if (replay != null) replay.observeTime(timestampNs);
        if (timestampNs > 0 && lastTimestampNs > 0 && timestampNs < lastTimestampNs) {
            patternEventSnapshotComplete = false;
            patternMonitorSnapshotComplete = false;
            // Legacy book state can retain pre-seek levels. Only fresh callbacks may seed the observer now.
            patternEventSeedFromSharedBook = false;
        }
        this.lastTimestampNs = timestampNs;
        if (premarketSizeThreshold != null) premarketSizeThreshold.onMarketTime();
        if (sharedServer != null) sharedServer.observeSessionTime(alias, timestampNs);
        if (bidBreakdownMonitor != null) {
            bidBreakdownMonitor.onTimestamp(timestampNs, patternMonitorSnapshotComplete);
            bidBreakdownPainter.refresh();
        }
        if (falseBreakoutMonitor != null) {
            falseBreakoutMonitor.onTimestamp(timestampNs, patternMonitorSnapshotComplete);
            falseBreakoutPainter.refresh();
        }
        if (reclaimSecondBidMonitor != null) {
            reclaimSecondBidMonitor.onTimestamp(timestampNs);
            reclaimSecondBidPainter.refresh();
        }
        flushPendingVwapPoints();
        synchronized (patternEventLock) {
            if (shouldRunPatternEvents()) patternEvents.onTimestamp(timestampNs, patternEventProvenance());
        }
        if (wallLabelTracker != null && wallLabelTracker.onTimestamp(timestampNs)) {
            wallLabelsDirty = true;
            refreshWallLabelsIfNeeded(true);
        }
    }

    @Override
    public void onBbo(int bidPrice, int bidSize, int askPrice, int askSize) {
        if (!initialized) return;
        if (sharedServer != null) sharedServer.observeSessionBbo(alias, bidPrice, askPrice, lastTimestampNs);
        if (falseBreakoutMonitor != null) {
            falseBreakoutMonitor.onBbo(bidPrice, askPrice, lastTimestampNs, patternMonitorSnapshotComplete);
            falseBreakoutPainter.refresh();
        }
        if (bidBreakdownMonitor != null) {
            bidBreakdownMonitor.onBbo(bidPrice, askPrice, lastTimestampNs, patternMonitorSnapshotComplete);
            bidBreakdownPainter.refresh();
        }
        if (reclaimSecondBidMonitor != null) {
            reclaimSecondBidMonitor.onBbo(bidPrice, askPrice, lastTimestampNs, patternMonitorSnapshotComplete);
            reclaimSecondBidPainter.refresh();
        }
        long eventTimeNs = getEventTimeNs();
        PatternEvent.TimestampProvenance provenance = patternEventProvenance();
        synchronized (patternEventLock) {
            if (shouldRunPatternEvents()) patternEvents.onBbo(bidPrice, askPrice, eventTimeNs, provenance);
        }
    }

    @Override
    public void onSnapshotEnd() {
        if (!initialized) return;
        patternMonitorSnapshotComplete = true;
        patternEventSnapshotComplete = true;
        if (wallChangeTracker != null) {
            wallChangeTracker.markReady();
        }
        markPatternEventsReady();
    }

    @Override
    public void onRealtimeStart() {
        if (!initialized) return;
        sessionHistoryLoading = false;
        if (sharedServer != null) sharedServer.finishSessionHistory(alias);
        patternMonitorSnapshotComplete = true;
        patternEventSnapshotComplete = true;
        // This callback alone does not prove a live provider (replay can catch up).
        if (wallChangeTracker != null) {
            wallChangeTracker.markReady();
        }
        markPatternEventsReady();
    }

    public static void resetNativeExecutionAfterBrokerReview() {
        if (nativeTrading != null) nativeTrading.resetAfterBrokerReview();
    }

    public static synchronized void restartNativeTrading() {
        if (nativeApis.isEmpty()) return;
        if (!LocalCredentials.fileExists()) return;
        if (sharedServer == null) return;
        if (nativeTrading != null) nativeTrading.close();
        nativeTrading = new NativeTradingAdapter(sharedServer, indicatorConfig, nativeConnectionStatus);
        nativeApis.forEach(nativeTrading::attach); nativeTrading.start();
    }
    public static void authorizeNativeTrading(String callback) { if (nativeTrading != null) nativeTrading.authorize(callback); }
    public static void openNativeAuthorization() { if (nativeTrading != null) nativeTrading.openAuthorization(); }
    public static String exportNativeExecutions(com.bookmap.plugin.rong.miniviteapp.core.account.ExecutionExports.Format format) {
        NativeTradingAdapter current = nativeTrading;
        if (current == null) throw new IllegalStateException("Native trading is unavailable");
        return current.exportExecutions(format);
    }

    @Override
    public void onIndicatorConfigChanged(String indicatorKey, boolean enabled) {
        if (!initialized) return;

        if (IndicatorConfig.VWAP.equals(indicatorKey)) {
            updateVwapIndicatorVisibility();
            if (enabled && vwapTracker != null) {
                vwapTracker.requestCurrentPoint(getEventTimeNs());
            }
            return;
        }
        if (IndicatorConfig.ORDER_WALL_CHANGE_ALERTS.equals(indicatorKey)) {
            if (!enabled && wallChangeStore != null && alias != null) {
                wallChangeStore.clearAll(alias);
            }
            return;
        }

    }

    private void handleWallChangeEvent(OrderWallChangeEvent event) {
        SignalWebSocketServer server = sharedServer;
        if (server != null && event.isQualifyingRetestFill()) {
            server.markEntryRetestSatisfied(event.getInstrumentAlias(), event.isBid());
        }
        if (indicatorConfig == null || !indicatorConfig.areOrderChangeAlertsEnabled()) {
            return;
        }
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

    private double getWallChangeDayHigh() {
        SignalWebSocketServer server = sharedServer;
        RegularSessionHighLowTracker.Snapshot snapshot = server == null ? null : server.getRegularSessionHighLow(alias);
        return snapshot == null ? Double.NaN : snapshot.getHighOfDay();
    }

    private double getWallChangeDayLow() {
        SignalWebSocketServer server = sharedServer;
        RegularSessionHighLowTracker.Snapshot snapshot = server == null ? null : server.getRegularSessionHighLow(alias);
        return snapshot == null ? Double.NaN : snapshot.getLowOfDay();
    }

    private com.google.gson.JsonObject cairoPatternSnapshot(boolean reconnect) {
        synchronized (patternEventLock) {
            if (patternEvents == null) return null;
            com.google.gson.JsonObject liquidity = new com.google.gson.JsonObject();
            synchronized (orderBook) {
                int threshold = DISPLAY_LIQUIDITY_MIN_SIZE;
                liquidity.addProperty("threshold", threshold);
                liquidity.addProperty("bestBid", BookmapPriceNormalizer.toWirePrice(patternEvents.bestBidTick(), instrumentInfo.pips));
                liquidity.addProperty("bestAsk", BookmapPriceNormalizer.toWirePrice(patternEvents.bestAskTick(), instrumentInfo.pips));
                liquidity.addProperty("asOf", Long.toString(getEventTimeNs()));
                for (boolean bid : new boolean[]{true, false}) {
                    com.google.gson.JsonArray levels = new com.google.gson.JsonArray();
                    orderBook.getLevelsSnapshot(bid).entrySet().stream().filter(e -> e.getValue() >= threshold)
                            .sorted(bid ? java.util.Map.Entry.<Integer,Integer>comparingByKey().reversed() : java.util.Map.Entry.comparingByKey())
                            .limit(8).forEach(e -> {
                                com.google.gson.JsonObject level = new com.google.gson.JsonObject();
                                level.addProperty("price", BookmapPriceNormalizer.toWirePrice(e.getKey(), instrumentInfo.pips));
                                level.addProperty("displayedSize", e.getValue()); levels.add(level);
                            });
                    liquidity.add(bid ? "bids" : "offers", levels);
                }
            }
            com.google.gson.JsonObject value = patternEvents.aggregateSnapshot(liquidity, reconnect);
            value.addProperty("mode", runMode.dataMode);
            return value;
        }
    }

    private boolean shouldRunPatternEvents() {
        return initialized && patternEvents != null
                && patternRules != null && patternRules.enabled && patternRules.eligible(alias);
    }
    private PatternEvent.TimestampProvenance patternEventProvenance() {
        return lastTimestampNs > 0 ? PatternEvent.TimestampProvenance.MARKET : PatternEvent.TimestampProvenance.FALLBACK;
    }
    private void markPatternEventsReady() {
        synchronized (patternEventLock) {
            if (!shouldRunPatternEvents()) return;
            if (patternEventSeedFromSharedBook && lastTimestampNs > 0 && orderBook != null) {
                for (boolean bid : new boolean[] {true, false}) {
                    for (java.util.Map.Entry<Integer, Integer> level : orderBook.getLevelsSnapshot(bid).entrySet())
                        patternEvents.onDepth(bid, level.getKey(), level.getValue(), lastTimestampNs, PatternEvent.TimestampProvenance.MARKET);
                }
            }
            patternEvents.markReady();
            if (lastTimestampNs > 0) patternEvents.onTimestamp(lastTimestampNs, PatternEvent.TimestampProvenance.MARKET);
        }
    }

    private boolean isEntryLiquidityReady() {
        if (sharedServer == null) return false;
        com.google.gson.JsonObject cap = sharedServer.entrySizeCap(alias);
        return com.bookmap.plugin.rong.miniviteapp.core.controllers.BookmapSizeCap.liquidityQualified(cap);
    }

    private int getEffectiveWallThreshold() {
        return premarketSizeThreshold == null ? PremarketSizeThreshold.FALLBACK : premarketSizeThreshold.threshold();
    }

    private void flushPendingVwapPoints() {
        VwapTracker tracker = vwapTracker;
        if (tracker == null) {
            return;
        }
        for (VwapTracker.VwapPoint point : tracker.drainPendingIndicatorPoints()) {
            addVwapPoint(point);
        }
    }

    private void addVwapPoint(VwapTracker.VwapPoint point) {
        if (point == null || vwapIndicator == null || indicatorConfig == null
                || !indicatorConfig.isEnabled(IndicatorConfig.VWAP)) {
            return;
        }
        addPrimaryPricePoint(vwapIndicator, point.getTimestampNs(), point.getValue());
    }

    /** Both price lines use the same real-price conversion and timestamped plotting API. */
    private void addPrimaryPricePoint(IndicatorModifiable indicator, long timestampNs, double realPrice) {
        double priceLevel = Double.isNaN(realPrice) ? Double.NaN
                : BookmapPriceNormalizer.toBookmapPriceLevel(realPrice, instrumentInfo.pips);
        indicator.addPoint(timestampNs, priceLevel);
    }

    private double getVwapTick() {
        VwapTracker tracker = vwapTracker;
        if (tracker == null || instrumentInfo == null) {
            return Double.NaN;
        }
        double vwap = tracker.getCurrentVwap();
        if (!BookmapPriceNormalizer.isValidWirePrice(vwap)) {
            return Double.NaN;
        }
        return BookmapPriceNormalizer.toBookmapPriceLevel(vwap, instrumentInfo.pips);
    }

    private void updateVwapIndicatorVisibility() {
        if (vwapIndicator == null || indicatorConfig == null) {
            return;
        }
        vwapIndicator.setColor(indicatorConfig.isEnabled(IndicatorConfig.VWAP)
                ? VWAP_COLOR
                : VWAP_HIDDEN_COLOR);
    }


    private void playWallChangeSound(OrderWallChangeEvent event) {
        if (api == null || indicatorConfig == null
                || !indicatorConfig.isOrderChangeSoundEnabled()
                || !event.isActiveLiquidityAlert()) {
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
            // Keep sound-alert failures inside Bookmap without writing diagnostic logs.
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
}
