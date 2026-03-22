package com.bookmap.plugin.common;

import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
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

    /** Callback invoked when a key+click resolves to a price on a chart. */
    @FunctionalInterface
    public interface ClickCallback {
        void onChartClick(String instrumentAlias, double priceInTicks, double realPrice, String keyCode);
    }

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

    private static volatile BufferedWriter clickLogWriter;
    private final SignalWebSocketServer wsServer;
    private volatile ClickCallback clickCallback;

    public ChartClickHandler(SignalWebSocketServer wsServer) {
        this.wsServer = wsServer;
        initClickLog();
        ensureAwtListener();
    }

    private static void initClickLog() {
        if (clickLogWriter != null) return;
        try {
            Path logFile = Paths.get(System.getProperty("user.home"), "ProgramLogs", "bookmap-signals", "click-debug.log");
            Files.createDirectories(logFile.getParent());
            clickLogWriter = Files.newBufferedWriter(logFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            PluginLog.error("[ActiveTrader] Failed to open click log: " + e.getMessage());
        }
    }

    private static void logClick(String msg) {
        PluginLog.info(msg);
        if (clickLogWriter != null) {
            try {
                clickLogWriter.write(System.currentTimeMillis() + " " + msg);
                clickLogWriter.newLine();
                clickLogWriter.flush();
            } catch (IOException ignored) {}
        }
    }

    /** Set a callback to be invoked on key+click price selection. */
    public void setClickCallback(ClickCallback callback) {
        this.clickCallback = callback;
    }

    /** Register an instrument's pips before the painter is created. */
    public void registerSymbol(String instrumentAlias, double pips) {
        instrumentPips.put(instrumentAlias, pips);
        lastRegisteredInstrument = instrumentAlias;
        PluginLog.info("[ActiveTrader] ChartClickHandler registered instrument: " + instrumentAlias + " pips=" + pips);
    }

    public void unregisterSymbol(String instrumentAlias) {
        instrumentPips.remove(instrumentAlias);
        // Clean up any painter mappings pointing to this instrument
        painterToInstrument.entrySet().removeIf(e -> e.getValue().equals(instrumentAlias));
    }

    /** Remove the global AWT listener so a fresh one can be registered on next init. */
    public static void removeAwtListener() {
        synchronized (listenerLock) {
            if (awtListener != null) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(awtListener);
                awtListener = null;
                heldKeys.clear();
                painterCoords.clear();
                painterToInstrument.clear();
                PluginLog.info("[ActiveTrader] AWT listener removed");
            }
        }
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

                int localY = me.getY();
                java.awt.Component comp = me.getComponent();
                int compHeight = (comp != null) ? comp.getHeight() : 0;

                logClick("[ActiveTrader] Click: localY=" + localY
                    + ", compHeight=" + compHeight
                    + ", keyCode=" + keyCode);

                // Find the painter whose chart contains the click (fraction in [0,1])
                String bestInstrument = null;
                double bestPrice = Double.NaN;
                double bestPriceTick = Double.NaN;

                for (Map.Entry<String, CoordinateState> entry : painterCoords.entrySet()) {
                    String painterAlias = entry.getKey();
                    CoordinateState cs = entry.getValue();
                    if (cs.pixelsHeight <= 0 || cs.priceHeight <= 0) continue;

                    String instrument = painterToInstrument.getOrDefault(painterAlias, lastRegisteredInstrument);
                    if (instrument == null) instrument = painterAlias;
                    double pips = instrumentPips.getOrDefault(instrument, 1.0);

                    double fraction = cs.fraction(localY, compHeight);
                    double priceTick = cs.yToPriceTick(localY, compHeight);
                    double price = priceTick * pips;

                    logClick("[ActiveTrader] Painter " + painterAlias + " → " + instrument
                        + ": fraction=" + String.format("%.4f", fraction)
                        + ", price=" + String.format("%.6f", price)
                        + ", pixelsBottom=" + cs.pixelsBottom + ", pixelsHeight=" + cs.pixelsHeight
                        + ", priceBottom=" + cs.priceBottom + ", priceHeight=" + cs.priceHeight);

                    if (fraction >= 0 && fraction <= 1 && !Double.isNaN(price) && price > 0) {
                        bestInstrument = instrument;
                        bestPrice = price;
                        bestPriceTick = priceTick;
                        break;
                    }
                }

                if (bestInstrument != null) {
                    String json = String.format(
                        "{\"type\":\"priceSelect\",\"symbol\":\"%s\",\"price\":%.6f,\"keyCode\":\"%s\",\"timestamp\":%d}",
                        bestInstrument, bestPrice, keyCode, System.currentTimeMillis());
                    wsServer.broadcastSignal(json);
                    logClick("[ActiveTrader] Price select: " + bestInstrument + " @ " + bestPrice
                        + " keyCode=" + keyCode);

                    // Invoke click callback for price line drawing
                    ClickCallback cb = clickCallback;
                    if (cb != null) {
                        cb.onChartClick(bestInstrument, bestPriceTick, bestPrice, keyCode);
                    }
                } else {
                    logClick("[ActiveTrader] Click: no painter matched bounds");
                }
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(awtListener,
                AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
            PluginLog.info("[ActiveTrader] AWT mouse listener registered for Cmd+Click");
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
        PluginLog.info("[ActiveTrader] ScreenSpacePainter created: painterAlias=" + alias
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
                    PluginLog.info("[ActiveTrader] Coordinate mapping active for painter " + alias
                        + ": priceBottom=" + coords.priceBottom + ", priceHeight=" + coords.priceHeight
                        + ", pixelsBottom=" + coords.pixelsBottom + ", pixelsHeight=" + coords.pixelsHeight);
                }
            }

            @Override
            public void dispose() {
                painterCoords.remove(alias);
                painterToInstrument.remove(alias);
                PluginLog.info("[ActiveTrader] ScreenSpacePainter disposed for " + alias);
            }
        };
    }

    /** Stores the chart coordinate mapping for one painter instance. */
    private static class CoordinateState {
        volatile long priceBottom;  // price at bottom of chart (in ticks)
        volatile long priceHeight;  // price range visible (in ticks)
        volatile int pixelsBottom;  // pixels from component bottom to heatmap bottom (bottom margin)
        volatile int pixelsHeight;  // pixel height of heatmap area

        /**
         * Compute where a click falls in the heatmap (0 = bottom, 1 = top).
         *
         * Bookmap's pixelsBottom is a bottom margin: the heatmap bottom in
         * component-local top-down coords is (compHeight - pixelsBottom).
         * localY is also in component-local top-down coords (from getY()).
         */
        double fraction(int localY, int compHeight) {
            if (pixelsHeight <= 0) return Double.NaN;
            int heatmapBottom = compHeight - pixelsBottom;
            return (double)(heatmapBottom - localY) / pixelsHeight;
        }

        /**
         * Convert a component-local Y coordinate to a price tick value (before pips).
         */
        double yToPriceTick(int localY, int compHeight) {
            if (pixelsHeight <= 0 || priceHeight <= 0) return Double.NaN;
            double f = fraction(localY, compHeight);
            return priceBottom + priceHeight * f;
        }
    }
}
