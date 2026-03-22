package com.bookmap.plugin.rong;

import java.util.List;

import velox.api.layer1.simplified.CustomSettingsPanelProvider;
import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.messages.indicators.Layer1ApiDataInterfaceRequestMessage;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyScreenSpacePainter;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.CustomModuleAdapter;
import velox.api.layer1.simplified.DepthDataListener;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.TimeListener;
import velox.api.layer1.simplified.TradeDataListener;
import velox.gui.StrategyPanel;

import com.bookmap.plugin.common.BreakoutSignal;
import com.bookmap.plugin.common.ChartClickHandler;
import com.bookmap.plugin.common.IndicatorConfig;
import com.bookmap.plugin.common.IndicatorSettingsPanel;
import com.bookmap.plugin.common.KeyBindingSettingsPanel;
import com.bookmap.plugin.common.OrderBookState;
import com.bookmap.plugin.common.OrderWallTracker;
import com.bookmap.plugin.common.PremarketTracker;
import com.bookmap.plugin.common.PriceLine;
import com.bookmap.plugin.common.PriceLineConfig;
import com.bookmap.plugin.common.PriceLinePainter;
import com.bookmap.plugin.common.PriceLineStore;
import com.bookmap.plugin.common.SignalWebSocketServer;
import com.bookmap.plugin.common.SwingLowDetector;
import com.bookmap.plugin.common.VwapTracker;
import com.bookmap.plugin.common.CamPivotTracker;
import com.bookmap.plugin.common.IndicatorDataFetcher;
import com.bookmap.plugin.common.KeyLevelConfig;
import com.bookmap.plugin.common.KeyLevelManager;
import com.bookmap.plugin.common.KeyLevelSettingsPanel;

@Layer1SimpleAttachable
@Layer1StrategyName("Rong")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION1)
public class RongPlugin implements CustomModuleAdapter,
        DepthDataListener, TradeDataListener, TimeListener, CustomSettingsPanelProvider {

    private static final int WS_PORT = 8765;
    private static final int WALL_THRESHOLD = 500_000;
    private static final double WALL_CONSUMED_RATIO = 0.20;
    private static final int SWING_LOOKBACK = 3;
    private static final int BAR_SIZE = 100;
    private static final double ORDERBOOK_PERCENTILE = 90;
    private static final int ORDERBOOK_INTERVAL_MS = 1000;

    // Shared WebSocket server across all symbol instances
    private static SignalWebSocketServer sharedServer;
    private static int instanceCount = 0;
    private static ChartClickHandler chartClickHandler;
    private static PriceLineStore priceLineStore;
    private static PriceLineConfig priceLineConfig;
    private static PriceLinePainter priceLinePainter;
    private static IndicatorConfig indicatorConfig;
    private static PremarketTracker premarketTracker;
    private static VwapTracker vwapTracker;
    private static KeyLevelConfig keyLevelConfig;
    private static KeyLevelManager keyLevelManager;
    private static CamPivotTracker camPivotTracker;

    private String alias;
    private Api api;
    private OrderWallTracker wallTracker;
    private SwingLowDetector swingDetector;
    private InstrumentInfo instrumentInfo;
    private OrderBookState orderBook;

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        this.alias = alias;
        this.api = api;
        this.instrumentInfo = info;
        this.orderBook = new OrderBookState();
        this.wallTracker = new OrderWallTracker(WALL_THRESHOLD, WALL_CONSUMED_RATIO);
        this.swingDetector = new SwingLowDetector(SWING_LOOKBACK, BAR_SIZE);

        synchronized (RongPlugin.class) {
            if (sharedServer == null) {
                sharedServer = new SignalWebSocketServer(WS_PORT, ORDERBOOK_PERCENTILE, ORDERBOOK_INTERVAL_MS);
                sharedServer.start();
                System.out.println("[Rong] Shared WebSocket server started on port " + WS_PORT);
            }
            if (chartClickHandler == null) {
                chartClickHandler = new ChartClickHandler(sharedServer);
            }
            if (priceLineStore == null) {
                priceLineStore = new PriceLineStore();
                priceLineConfig = new PriceLineConfig();
                priceLinePainter = new PriceLinePainter(priceLineStore);
                indicatorConfig = new IndicatorConfig();
                premarketTracker = new PremarketTracker(priceLineStore, indicatorConfig);
                vwapTracker = new VwapTracker(priceLineStore, indicatorConfig);
                keyLevelConfig = new KeyLevelConfig();
                keyLevelManager = new KeyLevelManager(keyLevelConfig, priceLineStore);
                camPivotTracker = new CamPivotTracker(priceLineStore, indicatorConfig);

                // Wire click callback: key+click creates a price line if key is bound
                chartClickHandler.setClickCallback((instrument, priceInTicks, realPrice, keyCode) -> {
                    PriceLine.LineType lineType = priceLineConfig.getLineType(keyCode);
                    if (lineType != null) {
                        PriceLine line = new PriceLine(instrument, lineType, priceInTicks, realPrice);
                        priceLineStore.addLine(line);
                        System.out.println("[Rong] Price line added: " + lineType.label
                                + " @ " + realPrice + " for " + instrument);
                    }
                });
            }
            instanceCount++;
        }
        sharedServer.registerSymbol(alias, orderBook, info.pips);
        chartClickHandler.registerSymbol(alias, info.pips);
        priceLinePainter.registerInstrument(alias);

        // Register ScreenSpacePainter to receive chart coordinate mappings
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, "clickHandler_" + alias)
                .setScreenSpacePainterFactory(chartClickHandler)
                .setIsAdd(true)
                .build());

        // Register ScreenSpacePainter for drawing price lines
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, "priceLines_" + alias)
                .setScreenSpacePainterFactory(priceLinePainter)
                .setIsAdd(true)
                .build());

        // Draw predefined key price levels for this instrument
        if (keyLevelManager != null) {
            keyLevelManager.onInstrumentInitialized(alias, info.pips);
        }

        // Fetch cam pivots + premarket high/low from EdgeDesk API (runs on background thread)
        IndicatorDataFetcher.fetch(alias, info.pips, camPivotTracker, premarketTracker);

        // Backfill VWAP and premarket from Bookmap historical data as fallback
        final String instrumentAlias = alias;
        final double pips = info.pips;
        final long initTime = initialState.getCurrentTime();
        api.sendUserMessage(new Layer1ApiDataInterfaceRequestMessage(dataInterface -> {
            if (dataInterface != null) {
                if (premarketTracker != null) {
                    premarketTracker.backfillFromHistory(dataInterface, instrumentAlias, pips, initTime);
                }
                if (vwapTracker != null) {
                    vwapTracker.backfillFromHistory(dataInterface, instrumentAlias, pips, initTime);
                }
            }
        }));

        System.out.println("[Rong] Plugin initialized for " + alias);
    }

    @Override
    public void stop() {
        if (chartClickHandler != null) {
            chartClickHandler.unregisterSymbol(alias);
        }
        if (priceLinePainter != null) {
            priceLinePainter.unregisterInstrument(alias);
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

        if (premarketTracker != null) {
            premarketTracker.unregister(alias);
        }
        if (vwapTracker != null) {
            vwapTracker.unregister(alias);
        }
        if (camPivotTracker != null) {
            camPivotTracker.unregister(alias);
        }
        if (keyLevelManager != null) {
            keyLevelManager.onInstrumentStopped(alias);
        }
        if (priceLineStore != null) {
            priceLineStore.clearAll(alias);
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
                if (vwapTracker != null) {
                    vwapTracker.shutdown();
                    vwapTracker = null;
                }
                if (camPivotTracker != null) {
                    camPivotTracker.shutdown();
                    camPivotTracker = null;
                }
                if (keyLevelManager != null) {
                    keyLevelManager.shutdown();
                    keyLevelManager = null;
                }
                keyLevelConfig = null;
                sharedServer.shutdown();
                sharedServer = null;
                chartClickHandler = null;
                priceLineStore = null;
                priceLineConfig = null;
                priceLinePainter = null;
                indicatorConfig = null;
                instanceCount = 0;
                System.out.println("[Rong] Shared WebSocket server shut down");
            }
        }
        System.out.println("[Rong] Plugin stopped for " + alias);
    }

    @Override
    public StrategyPanel[] getCustomSettingsPanels() {
        return new StrategyPanel[] {
            new KeyBindingSettingsPanel(priceLineConfig, priceLineStore),
            new IndicatorSettingsPanel(indicatorConfig),
            new KeyLevelSettingsPanel(keyLevelConfig)
        };
    }

    @Override
    public void onDepth(boolean isBid, int price, int size) {
        orderBook.update(isBid, price, size);
        wallTracker.updateLevel(isBid, price, size);
    }

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        double realPrice = price * instrumentInfo.pips;
        int priceTick = (int) price;

        swingDetector.addPrice(realPrice);
        sharedServer.setLastPrice(alias, realPrice);
        checkBreakout(realPrice, priceTick);
        wallTracker.cleanup(priceTick);

        if (premarketTracker != null) {
            premarketTracker.onTrade(alias, price, realPrice);
        }
        if (vwapTracker != null) {
            vwapTracker.onTrade(alias, price, realPrice, size);
        }
    }

    @Override
    public void onTimestamp(long timestampNs) {
        if (premarketTracker != null) {
            premarketTracker.setTimestamp(timestampNs);
        }
        if (vwapTracker != null) {
            vwapTracker.setTimestamp(timestampNs);
        }
    }

    private void checkBreakout(double currentPrice, int currentPriceTick) {
        List<OrderWallTracker.WallInfo> walls = wallTracker.getActiveWalls();
        for (OrderWallTracker.WallInfo wall : walls) {
            double wallRealPrice = wall.priceTick * instrumentInfo.pips;
            if (currentPrice > wallRealPrice && wallTracker.isConsumed(wall)) {
                double swingLow = swingDetector.getLastSwingLow();
                if (!Double.isNaN(swingLow) && swingLow < wallRealPrice) {
                    BreakoutSignal signal = new BreakoutSignal(alias, wallRealPrice, swingLow);
                    sharedServer.broadcastSignal(signal.toJson());
                    System.out.println("[Rong] BREAKOUT signal: " + signal.toJson());
                }
                wallTracker.removeWall(wall.priceTick);
            }
        }
    }
}
