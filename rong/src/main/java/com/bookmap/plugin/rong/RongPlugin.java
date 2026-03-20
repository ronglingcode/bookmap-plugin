package com.bookmap.plugin.rong;

import java.util.List;

import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyScreenSpacePainter;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.CustomModuleAdapter;
import velox.api.layer1.simplified.DepthDataListener;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.TradeDataListener;

import com.bookmap.plugin.common.BreakoutSignal;
import com.bookmap.plugin.common.ChartClickHandler;
import com.bookmap.plugin.common.OrderBookState;
import com.bookmap.plugin.common.OrderWallTracker;
import com.bookmap.plugin.common.SignalWebSocketServer;
import com.bookmap.plugin.common.SwingLowDetector;

@Layer1SimpleAttachable
@Layer1StrategyName("Rong")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION1)
public class RongPlugin implements CustomModuleAdapter,
        DepthDataListener, TradeDataListener {

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
            instanceCount++;
        }
        sharedServer.registerSymbol(alias, orderBook, info.pips);
        chartClickHandler.registerSymbol(alias, info.pips);

        // Register ScreenSpacePainter to receive chart coordinate mappings
        // Tag must be unique per instrument so each chart gets its own painter
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, "clickHandler_" + alias)
                .setScreenSpacePainterFactory(chartClickHandler)
                .setIsAdd(true)
                .build());

        System.out.println("[Rong] Plugin initialized for " + alias);
    }

    @Override
    public void stop() {
        if (chartClickHandler != null) {
            chartClickHandler.unregisterSymbol(alias);
        }
        // Unregister ScreenSpacePainter
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter.builder(
                RongPlugin.class, "clickHandler_" + alias)
                .setScreenSpacePainterFactory(chartClickHandler)
                .setIsAdd(false)
                .build());

        if (sharedServer != null) {
            sharedServer.unregisterSymbol(alias);
        }
        synchronized (RongPlugin.class) {
            instanceCount--;
            if (instanceCount <= 0 && sharedServer != null) {
                ChartClickHandler.removeAwtListener();
                sharedServer.shutdown();
                sharedServer = null;
                chartClickHandler = null;
                instanceCount = 0;
                System.out.println("[Rong] Shared WebSocket server shut down");
            }
        }
        System.out.println("[Rong] Plugin stopped for " + alias);
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
