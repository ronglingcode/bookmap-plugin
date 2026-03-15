package com.bookmap.plugin.activetrader;

import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
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
 * The painter's alias parameter is the full painter name (e.g. "BookmapActiveTraderPlugin#clickHandler"),
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

    /** Currently held non-modifier keys (e.g. 'b', 's'). Tracked via KEY_PRESSED/KEY_RELEASED. */
    private static final Set<String> heldKeys = Collections.newSetFromMap(new ConcurrentHashMap<>());

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
        System.out.println("[ActiveTrader] ChartClickHandler registered instrument: " + instrumentAlias + " pips=" + pips);
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
                // Track key press/release state
                if (event.getID() == KeyEvent.KEY_PRESSED) {
                    KeyEvent ke = (KeyEvent) event;
                    String key = KeyEvent.getKeyText(ke.getKeyCode()).toLowerCase();
                    heldKeys.add(key);
                    return;
                }
                if (event.getID() == KeyEvent.KEY_RELEASED) {
                    KeyEvent ke = (KeyEvent) event;
                    heldKeys.remove(KeyEvent.getKeyText(ke.getKeyCode()).toLowerCase());
                    return;
                }

                if (event.getID() != MouseEvent.MOUSE_CLICKED) return;
                MouseEvent me = (MouseEvent) event;

                // Require at least one key held during click (any key works)
                if (heldKeys.isEmpty() && !me.isMetaDown() && !me.isControlDown()
                        && !me.isShiftDown() && !me.isAltDown()) return;

                // Build keyCode from all held keys
                String keyCode = String.join("+", heldKeys);
                if (keyCode.isEmpty()) {
                    // Only modifier flags detected (no KEY_PRESSED events for modifiers on some platforms)
                    if (me.isMetaDown() || me.isControlDown()) keyCode = "cmd";
                    else if (me.isShiftDown()) keyCode = "shift";
                    else if (me.isAltDown()) keyCode = "alt";
                }

                int mouseY = me.getYOnScreen();
                System.out.println("[ActiveTrader] Click detected at screenY=" + mouseY
                    + ", keyCode=" + keyCode
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
                            "{\"type\":\"priceSelect\",\"symbol\":\"%s\",\"price\":%.6f,\"keyCode\":\"%s\",\"timestamp\":%d}",
                            instrument, price, keyCode, System.currentTimeMillis());
                        wsServer.broadcastSignal(json);
                        System.out.println("[ActiveTrader] Price select: " + instrument + " @ " + price
                            + " keyCode=" + keyCode);
                        return;
                    }
                }
                System.out.println("[ActiveTrader] Click: no valid coordinate mapping found");
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(awtListener,
                AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
            System.out.println("[ActiveTrader] AWT mouse listener registered for Cmd+Click");
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
        System.out.println("[ActiveTrader] ScreenSpacePainter created: painterAlias=" + alias
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
                    System.out.println("[ActiveTrader] Coordinate mapping active for painter " + alias
                        + ": priceBottom=" + coords.priceBottom + ", priceHeight=" + coords.priceHeight
                        + ", pixelsBottom=" + coords.pixelsBottom + ", pixelsHeight=" + coords.pixelsHeight);
                }
            }

            @Override
            public void dispose() {
                painterCoords.remove(alias);
                painterToInstrument.remove(alias);
                System.out.println("[ActiveTrader] ScreenSpacePainter disposed for " + alias);
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
