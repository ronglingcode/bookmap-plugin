package com.bookmap.plugin.wallbreakout;

import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterAdapter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterFactory;

/**
 * Handles Cmd+Click on Bookmap chart to select a price level.
 *
 * Uses ScreenSpacePainterAdapter to receive chart coordinate mappings,
 * and a global AWT mouse listener to detect Cmd+Click events.
 * Converts the click Y coordinate to a price and broadcasts it via WebSocket.
 *
 * NOTE: The ScreenSpacePainterFactory creates one painter per chart.
 * The painter's alias parameter is the full painter name (e.g. "WallBreakoutPlugin#clickHandler"),
 * NOT the instrument symbol. We maintain a separate mapping of painterAlias → instrumentAlias
 * to resolve the correct symbol when broadcasting.
 */
public class ChartClickHandler implements ScreenSpacePainterFactory {

    /** Coordinate state keyed by painter alias (from createScreenSpacePainter). */
    private static final Map<String, CoordinateState> painterCoords = new ConcurrentHashMap<>();

    /** Map painter alias → instrument alias (e.g. "AAPL"). Set in createScreenSpacePainter. */
    private static final Map<String, String> painterToInstrument = new ConcurrentHashMap<>();

    /** Instrument alias → pips. Set by registerSymbol before painter is created. */
    private static final Map<String, Double> instrumentPips = new ConcurrentHashMap<>();

    /** The most recently registered instrument alias — used as default. */
    private static volatile String lastRegisteredInstrument;

    /** Shared AWT listener — registered once. */
    private static volatile AWTEventListener awtListener;
    private static final Object listenerLock = new Object();

    private final SignalWebSocketServer wsServer;

    public ChartClickHandler(SignalWebSocketServer wsServer) {
        this.wsServer = wsServer;
        ensureAwtListener();
    }

    /** Register an instrument's pips before the painter is created. */
    public void registerSymbol(String instrumentAlias, double pips) {
        instrumentPips.put(instrumentAlias, pips);
        lastRegisteredInstrument = instrumentAlias;
        System.out.println("[WallBreakout] ChartClickHandler registered instrument: " + instrumentAlias + " pips=" + pips);
    }

    public void unregisterSymbol(String instrumentAlias) {
        instrumentPips.remove(instrumentAlias);
        // Clean up any painter mappings pointing to this instrument
        painterToInstrument.entrySet().removeIf(e -> e.getValue().equals(instrumentAlias));
    }

    private void ensureAwtListener() {
        if (awtListener != null) return;
        synchronized (listenerLock) {
            if (awtListener != null) return;
            awtListener = event -> {
                if (event.getID() != MouseEvent.MOUSE_CLICKED) return;
                MouseEvent me = (MouseEvent) event;
                if (!me.isMetaDown() && !me.isControlDown()) return; // Cmd+Click (macOS) or Ctrl+Click (Windows)

                int mouseY = me.getYOnScreen();
                System.out.println("[WallBreakout] Cmd+Click detected at screenY=" + mouseY
                    + ", painters tracked: " + painterCoords.keySet()
                    + ", instruments: " + painterToInstrument.values());

                for (Map.Entry<String, CoordinateState> entry : painterCoords.entrySet()) {
                    String painterAlias = entry.getKey();
                    CoordinateState cs = entry.getValue();
                    if (cs.pixelsHeight <= 0 || cs.priceHeight <= 0) continue;

                    // Resolve instrument alias
                    String instrument = painterToInstrument.getOrDefault(painterAlias, lastRegisteredInstrument);
                    if (instrument == null) instrument = painterAlias;

                    // Get pips for this instrument
                    double pips = instrumentPips.getOrDefault(instrument, 1.0);

                    double price = cs.yToPrice(mouseY, pips);
                    if (!Double.isNaN(price) && price > 0) {
                        String json = String.format(
                            "{\"type\":\"priceSelect\",\"symbol\":\"%s\",\"price\":%.6f,\"timestamp\":%d}",
                            instrument, price, System.currentTimeMillis());
                        wsServer.broadcastSignal(json);
                        System.out.println("[WallBreakout] Cmd+Click price select: " + instrument + " @ " + price
                            + " (pips=" + pips
                            + ", priceBottom=" + cs.priceBottom + ", priceHeight=" + cs.priceHeight
                            + ", pixelsBottom=" + cs.pixelsBottom + ", pixelsHeight=" + cs.pixelsHeight + ")");
                        return;
                    }
                }
                System.out.println("[WallBreakout] Cmd+Click: no valid coordinate mapping found");
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(awtListener, AWTEvent.MOUSE_EVENT_MASK);
            System.out.println("[WallBreakout] AWT mouse listener registered for Cmd+Click");
        }
    }

    @Override
    public ScreenSpacePainter createScreenSpacePainter(String alias, String fullName,
                                                        ScreenSpaceCanvasFactory canvasFactory) {
        CoordinateState coords = new CoordinateState();
        painterCoords.put(alias, coords);

        // Map this painter alias to the most recently registered instrument
        if (lastRegisteredInstrument != null) {
            painterToInstrument.put(alias, lastRegisteredInstrument);
        }
        System.out.println("[WallBreakout] ScreenSpacePainter created: painterAlias=" + alias
            + " → instrument=" + painterToInstrument.get(alias));

        return new ScreenSpacePainterAdapter() {
            private boolean logged = false;

            @Override
            public void onHeatmapPriceBottom(long priceBottom) {
                coords.priceBottom = priceBottom;
                logOnce();
            }

            @Override
            public void onHeatmapPriceHeight(long priceHeight) {
                coords.priceHeight = priceHeight;
            }

            @Override
            public void onHeatmapPixelsBottom(int pixelsBottom) {
                coords.pixelsBottom = pixelsBottom;
            }

            @Override
            public void onHeatmapPixelsHeight(int pixelsHeight) {
                coords.pixelsHeight = pixelsHeight;
            }

            private void logOnce() {
                if (!logged && coords.priceHeight > 0 && coords.pixelsHeight > 0) {
                    logged = true;
                    System.out.println("[WallBreakout] Coordinate mapping active for painter " + alias
                        + ": priceBottom=" + coords.priceBottom + ", priceHeight=" + coords.priceHeight
                        + ", pixelsBottom=" + coords.pixelsBottom + ", pixelsHeight=" + coords.pixelsHeight);
                }
            }

            @Override
            public void dispose() {
                painterCoords.remove(alias);
                painterToInstrument.remove(alias);
                System.out.println("[WallBreakout] ScreenSpacePainter disposed for " + alias);
            }
        };
    }

    /** Stores the chart coordinate mapping for one painter instance. */
    private static class CoordinateState {
        volatile long priceBottom;  // price at bottom of chart (in ticks)
        volatile long priceHeight;  // price range visible (in ticks)
        volatile int pixelsBottom;  // Y pixel of chart bottom edge
        volatile int pixelsHeight;  // pixel height of chart area

        /**
         * Convert a screen Y coordinate to a real price.
         * Y increases downward in screen coords, but price increases upward.
         */
        double yToPrice(int screenY, double pips) {
            if (pixelsHeight <= 0 || priceHeight <= 0) return Double.NaN;
            double fraction = (double)(pixelsBottom - screenY) / pixelsHeight;
            double priceTick = priceBottom + priceHeight * fraction;
            return priceTick * pips;
        }
    }
}
