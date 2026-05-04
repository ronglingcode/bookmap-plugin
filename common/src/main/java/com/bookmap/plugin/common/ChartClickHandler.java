package com.bookmap.plugin.common;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.Window;
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
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

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

    /** Prefix used when registering this painter (see plugin initialize methods). */
    public static final String PAINTER_NAME_PREFIX = "clickHandler_";

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

    /** The most recently registered instrument alias — used as last-resort default. */
    private static volatile String lastRegisteredInstrument;

    /** Cache: clicked top-level Window → resolved instrument alias.
     *  Avoids re-walking the AWT tree on every click. */
    private static final Map<Window, String> windowToInstrument = new ConcurrentHashMap<>();

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
            Path logFile = Paths.get(System.getProperty("user.home"), "Bookmap", "bookmap-signals", "click-debug.log");
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
        // Drop cached window mappings so a re-opened chart resolves fresh
        windowToInstrument.entrySet().removeIf(e -> e.getValue().equals(instrumentAlias));
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
                windowToInstrument.clear();
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

                // Identify which chart was actually clicked by inspecting the AWT hierarchy
                // of the clicked component. This is the fix for "click on stock A sends price
                // for stock B" — multiple painters may have valid fractions when chart layouts
                // are similar, so we must use the actual clicked component as the source of truth.
                String clickedInstrument = identifyInstrumentFromComponent(comp);

                logClick("[ActiveTrader] Click: localY=" + localY
                    + ", compHeight=" + compHeight
                    + ", keyCode=" + keyCode
                    + ", clickedInstrument=" + clickedInstrument);

                // Find the painter whose chart contains the click (fraction in [0,1]).
                // If we identified the chart from the AWT hierarchy, only consider painters
                // that map to that instrument.
                String bestInstrument = null;
                double bestPrice = Double.NaN;
                double bestPriceTick = Double.NaN;

                for (Map.Entry<String, CoordinateState> entry : painterCoords.entrySet()) {
                    String painterAlias = entry.getKey();
                    CoordinateState cs = entry.getValue();
                    if (cs.pixelsHeight <= 0 || cs.priceHeight <= 0) continue;

                    String instrument = painterToInstrument.getOrDefault(painterAlias, lastRegisteredInstrument);
                    if (instrument == null) instrument = painterAlias;

                    // If we know which instrument was clicked, skip painters for other instruments.
                    if (clickedInstrument != null && !clickedInstrument.equals(instrument)) {
                        continue;
                    }

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

        // Resolve the instrument by parsing the painter name we registered with
        // (Layer1ApiUserMessageModifyScreenSpacePainter.builder(..., "clickHandler_<symbol>")).
        // The Bookmap API exposes that name in either `alias` or `fullName` — search both.
        // Falls back to `lastRegisteredInstrument` only if parsing fails (best-effort legacy path).
        String instrument = extractInstrumentFromPainterName(alias, fullName);
        if (instrument == null) {
            instrument = lastRegisteredInstrument;
        }
        if (instrument != null) {
            painterToInstrument.put(alias, instrument);
        }
        PluginLog.info("[ActiveTrader] ScreenSpacePainter created: painterAlias=" + alias
            + " fullName=" + fullName
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

    /**
     * Extract an instrument alias from the painter alias / fullName params passed to
     * createScreenSpacePainter. We register painters with name "clickHandler_" + symbol,
     * and Bookmap typically wraps that into something like
     * "BookmapActiveTraderPlugin#clickHandler_AAPL". So we look for our prefix and take
     * everything after it.
     */
    static String extractInstrumentFromPainterName(String painterAlias, String fullName) {
        String[] candidates = {painterAlias, fullName};
        for (String s : candidates) {
            if (s == null) continue;
            int idx = s.indexOf(PAINTER_NAME_PREFIX);
            if (idx >= 0) {
                String tail = s.substring(idx + PAINTER_NAME_PREFIX.length());
                // Tail might have additional suffix separators on some platforms — keep up to
                // the first separator only.
                int sep = indexOfAny(tail, '#', '/', ' ');
                if (sep >= 0) tail = tail.substring(0, sep);
                if (!tail.isEmpty()) return tail;
            }
        }
        return null;
    }

    private static int indexOfAny(String s, char... chars) {
        int best = -1;
        for (char c : chars) {
            int i = s.indexOf(c);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    /**
     * Determine which registered instrument the click belongs to by inspecting the AWT
     * hierarchy of the clicked component. Strategies (in order):
     *   1) Cached lookup by top-level Window
     *   2) Window title contains a known instrument alias
     *   3) Any ancestor's Component name contains a known instrument alias
     *   4) Recursive search of the window for a JLabel whose text contains a known alias
     * Returns null if nothing matches; callers should fall back to the legacy heuristic.
     */
    static String identifyInstrumentFromComponent(Component clickedComp) {
        if (clickedComp == null) return null;

        Window window = SwingUtilities.getWindowAncestor(clickedComp);
        if (window != null) {
            String cached = windowToInstrument.get(window);
            if (cached != null && instrumentPips.containsKey(cached)) {
                return cached;
            }
        }

        Set<String> known = instrumentPips.keySet();
        if (known.isEmpty()) return null;

        // Strategy 2: window title
        if (window instanceof Frame) {
            String title = ((Frame) window).getTitle();
            String hit = findKnownAliasIn(title, known);
            if (hit != null) {
                windowToInstrument.put(window, hit);
                return hit;
            }
        }

        // Strategy 3: walk up parent chain of clicked component, check each Component.getName()
        Component c = clickedComp;
        while (c != null) {
            String hit = findKnownAliasIn(c.getName(), known);
            if (hit != null) {
                if (window != null) windowToInstrument.put(window, hit);
                return hit;
            }
            c = c.getParent();
        }

        // Strategy 4: recursive search across the whole window for any JLabel/Component name
        if (window != null) {
            String hit = searchTreeForKnownAlias(window, known);
            if (hit != null) {
                windowToInstrument.put(window, hit);
                return hit;
            }
        }

        return null;
    }

    private static String findKnownAliasIn(String text, Set<String> known) {
        if (text == null || text.isEmpty()) return null;
        for (String alias : known) {
            if (alias != null && !alias.isEmpty() && text.contains(alias)) {
                return alias;
            }
        }
        return null;
    }

    private static String searchTreeForKnownAlias(Component root, Set<String> known) {
        if (root == null) return null;
        String hit = findKnownAliasIn(root.getName(), known);
        if (hit != null) return hit;
        if (root instanceof JLabel) {
            hit = findKnownAliasIn(((JLabel) root).getText(), known);
            if (hit != null) return hit;
        }
        if (root instanceof Frame) {
            hit = findKnownAliasIn(((Frame) root).getTitle(), known);
            if (hit != null) return hit;
        }
        if (root instanceof Container) {
            for (Component child : ((Container) root).getComponents()) {
                hit = searchTreeForKnownAlias(child, known);
                if (hit != null) return hit;
            }
        }
        return null;
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
