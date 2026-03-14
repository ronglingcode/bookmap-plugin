package com.bookmap.plugin.wallbreakout;

import java.util.List;

import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.CustomModuleAdapter;
import velox.api.layer1.simplified.DepthDataListener;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.TradeDataListener;

@Layer1SimpleAttachable
@Layer1StrategyName("Wall Breakout Detector")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION1)
public class WallBreakoutPlugin implements CustomModuleAdapter,
        DepthDataListener, TradeDataListener {

    private static final int WS_PORT = 8765;
    private static final int WALL_THRESHOLD = 500_000;
    private static final double WALL_CONSUMED_RATIO = 0.20;
    private static final int SWING_LOOKBACK = 3;
    private static final int BAR_SIZE = 100;
    private static final double ORDERBOOK_PERCENTILE = 90; // only broadcast levels above this percentile (0 = no filter)
    private static final int ORDERBOOK_INTERVAL_MS = 1000; // how often to broadcast orderbook snapshots

    private OrderWallTracker wallTracker;
    private SwingLowDetector swingDetector;
    private SignalWebSocketServer wsServer;
    private InstrumentInfo instrumentInfo;
    private OrderBookState orderBook;

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        this.instrumentInfo = info;
        this.orderBook = new OrderBookState();
        this.wallTracker = new OrderWallTracker(WALL_THRESHOLD, WALL_CONSUMED_RATIO);
        this.swingDetector = new SwingLowDetector(SWING_LOOKBACK, BAR_SIZE);
        this.wsServer = new SignalWebSocketServer(WS_PORT, orderBook, ORDERBOOK_PERCENTILE, ORDERBOOK_INTERVAL_MS);
        this.wsServer.setPips(info.pips);
        this.wsServer.start();
        System.out.println("[WallBreakout] Plugin initialized for " + alias);
    }

    @Override
    public void stop() {
        if (wsServer != null) {
            wsServer.shutdown();
        }
        System.out.println("[WallBreakout] Plugin stopped");
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
        wsServer.setLastPrice(realPrice);
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
                    BreakoutSignal signal = new BreakoutSignal(wallRealPrice, swingLow);
                    wsServer.broadcastSignal(signal.toJson());
                    System.out.println("[WallBreakout] BREAKOUT signal: " + signal.toJson());
                }
                wallTracker.removeWall(wall.priceTick);
            }
        }
    }
}
