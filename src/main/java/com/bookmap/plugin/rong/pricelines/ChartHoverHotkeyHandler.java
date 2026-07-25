package com.bookmap.plugin.rong.pricelines;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.TextComponent;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

import com.bookmap.plugin.rong.IndicatorConfig;
import com.bookmap.plugin.rong.PluginLog;
import com.bookmap.plugin.rong.SignalWebSocketServer;
import com.google.gson.JsonObject;

import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterAdapter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterFactory;

/**
 * Forwards supported keyboard actions with the currently hovered Bookmap chart price.
 *
 * Uses ScreenSpacePainterAdapter to receive chart coordinate mappings,
 * a global AWT mouse-motion listener to track the current chart price, and a
 * keyboard listener to broadcast supported hotkeys via WebSocket.
 *
 * NOTE: The ScreenSpacePainterFactory creates one painter per chart.
 * The painter's alias parameter is the full painter name (e.g. "RongPlugin#hoverHotkey"),
 * NOT the instrument symbol. We maintain a separate mapping of painterAlias → instrumentAlias
 * to resolve the correct symbol when broadcasting.
 */
public class ChartHoverHotkeyHandler implements ScreenSpacePainterFactory {

    /** Prefix used when registering this painter (see plugin initialize methods). */
    public static final String PAINTER_NAME_PREFIX = "hoverHotkey_";
    private static final ZoneId NEW_YORK_TIME_ZONE = ZoneId.of("America/New_York");
    private static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(16, 0);

    /** Coordinate state keyed by painter alias (from createScreenSpacePainter). */
    private static final Map<String, CoordinateState> painterCoords = new ConcurrentHashMap<>();

    /** Map painter alias → instrument alias (e.g. "AAPL"). Set in createScreenSpacePainter. */
    private static final Map<String, String> painterToInstrument = new ConcurrentHashMap<>();

    /** Instrument alias → pips. Set by registerSymbol before painter is created. */
    private static final Map<String, Double> instrumentPips = new ConcurrentHashMap<>();

    /** The most recently registered instrument alias — used as last-resort default. */
    private static volatile String lastRegisteredInstrument;

    /** Cache: hovered top-level Window → resolved instrument alias.
     *  Avoids re-walking the AWT tree on every mouse move. */
    private static final Map<Window, String> windowToInstrument = new ConcurrentHashMap<>();

    /** Currently held non-modifier keys (e.g. 'b', 's'). Tracked via KEY_PRESSED/KEY_RELEASED. */
    private static final Set<String> heldKeys = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Chart hotkeys forwarded to ViteApp with the currently hovered Bookmap price. */
    private static final Set<String> CHART_HOTKEYS =
            Set.of(
                    "a", "g", "t", "w",
                    "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
                    "numpad1", "numpad2", "numpad3", "numpad4", "numpad5",
                    "numpad6", "numpad7", "numpad8", "numpad9", "numpad0");

    /** Last resolved chart hover. Used so KEY_PRESSED can include the hovered chart price. */
    private static volatile HoverContext lastHoverContext;

    /** Shared AWT listener — registered once. */
    private static volatile AWTEventListener awtListener;
    private static final Object listenerLock = new Object();

    private final SignalWebSocketServer wsServer;
    private final IndicatorConfig config;

    public ChartHoverHotkeyHandler(SignalWebSocketServer wsServer, IndicatorConfig config) {
        this.wsServer = wsServer;
        this.config = config;
        ensureAwtListener();
    }

    /** Register an instrument's pips before the painter is created. */
    public void registerSymbol(String instrumentAlias, double pips) {
        instrumentPips.put(instrumentAlias, pips);
        lastRegisteredInstrument = instrumentAlias;
        PluginLog.info("[Rong] ChartHoverHotkeyHandler registered instrument: "
                + instrumentAlias + " pips=" + pips);
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
                lastHoverContext = null;
                PluginLog.info("[Rong] AWT listener removed");
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
                    String key = normalizeKey(ke);
                    boolean firstPress = heldKeys.add(key);
                    if (firstPress) {
                        handleChartHotkey(ke, key);
                    }
                    return;
                }
                if (event.getID() == KeyEvent.KEY_RELEASED) {
                    KeyEvent ke = (KeyEvent) event;
                    heldKeys.remove(normalizeKey(ke));
                    return;
                }

                if (event.getID() == MouseEvent.MOUSE_MOVED || event.getID() == MouseEvent.MOUSE_DRAGGED) {
                    updateHoverContext((MouseEvent) event);
                    return;
                }
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(awtListener,
                AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
            PluginLog.info("[Rong] AWT listener registered for chart hover hotkeys");
        }
    }

    private void updateHoverContext(MouseEvent event) {
        Component component = event.getComponent();
        ResolvedChartPrice price = resolveChartPrice(component, event.getY());
        lastHoverContext = price == null ? null : new HoverContext(price.instrument, price.price, component);
    }

    private void handleChartHotkey(KeyEvent event, String normalizedKey) {
        if (!isChartHotkey(normalizedKey)) {
            return;
        }
        if (config == null || !config.isEnabled(IndicatorConfig.FIRE_KEYBOARD_EVENT)) {
            return;
        }
        if (isTextEntryEvent(event)) {
            PluginLog.info("[Rong] Chart hotkey blocked while text entry appears active: key="
                    + normalizedKey + ", focus=" + describeComponent(
                            KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner())
                    + ", source=" + describeComponent(event.getComponent()));
            return;
        }

        HoverContext hover = resolveCurrentHoverContext();
        if (hover == null) {
            return;
        }

        String keyCode = toViteKeyCode(normalizedKey);
        boolean shiftDown = event.isShiftDown() || heldKeys.contains("shift");
        JsonObject json = new JsonObject();
        json.addProperty("type", "custom_button_click");
        json.addProperty("symbol", hover.instrument);
        json.addProperty("button_id", "chart_hotkey:" + normalizedKey);
        json.addProperty("button_name", "Chart Hotkey " + normalizedKey.toUpperCase());
        json.addProperty("keyCode", keyCode);
        json.addProperty("key_code", keyCode);
        json.addProperty("shiftKey", shiftDown);
        json.addProperty("shift_key", shiftDown);
        json.addProperty("price", hover.price);
        json.addProperty("source", "bookmap_chart_hotkey");
        json.addProperty("timestamp", System.currentTimeMillis());

        String actionLog = formatHoverHotkeyActionLog(
                hover.instrument, keyCode, hover.price, shiftDown);
        PluginLog.action(hover.instrument, "Bookmap", actionLog);
        if (isAfterMarketClose(Instant.now())) {
            PluginLog.info("[Rong] " + actionLog
                    + " logged but not sent after the 4:00 PM New York market close");
            return;
        }

        wsServer.broadcast(json.toString());
        PluginLog.info("[Rong] " + actionLog + " sent");
    }

    private static HoverContext resolveCurrentHoverContext() {
        HoverContext hover = lastHoverContext;
        if (hover == null || hover.component == null || !hover.component.isShowing()) {
            return null;
        }

        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
        if (pointerInfo == null) {
            return null;
        }

        Point point = pointerInfo.getLocation();
        SwingUtilities.convertPointFromScreen(point, hover.component);
        if (!hover.component.contains(point)) {
            return null;
        }

        ResolvedChartPrice current = resolveChartPrice(hover.component, point.y);
        if (current == null) {
            lastHoverContext = null;
            return null;
        }

        HoverContext refreshed = new HoverContext(current.instrument, current.price, hover.component);
        lastHoverContext = refreshed;
        return refreshed;
    }

    private static ResolvedChartPrice resolveChartPrice(Component comp, int localY) {
        int compHeight = (comp != null) ? comp.getHeight() : 0;
        String componentInstrument = identifyInstrumentFromComponent(comp);

        for (Map.Entry<String, CoordinateState> entry : painterCoords.entrySet()) {
            String painterAlias = entry.getKey();
            CoordinateState cs = entry.getValue();
            if (cs.pixelsHeight <= 0 || cs.priceHeight <= 0) continue;

            String instrument = painterToInstrument.getOrDefault(painterAlias, lastRegisteredInstrument);
            if (instrument == null) instrument = painterAlias;

            if (componentInstrument != null && !componentInstrument.equals(instrument)) {
                continue;
            }

            double pips = instrumentPips.getOrDefault(instrument, 1.0);
            double fraction = cs.fraction(localY, compHeight);
            double priceTick = cs.yToPriceTick(localY, compHeight);
            double price = priceTick * pips;

            if (fraction >= 0 && fraction <= 1 && !Double.isNaN(price) && price > 0) {
                return new ResolvedChartPrice(instrument, price);
            }
        }

        return null;
    }

    private static boolean isTextEntryEvent(KeyEvent event) {
        if (event == null || event.isConsumed()) {
            return true;
        }

        KeyboardFocusManager manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        if (isTextEntryComponent(manager.getFocusOwner())
                || isTextEntryComponent(manager.getPermanentFocusOwner())
                || isTextEntryComponent(event.getComponent())) {
            return true;
        }

        Object source = event.getSource();
        if (source instanceof Component && isTextEntryComponent((Component) source)) {
            return true;
        }

        return hasTextEntryMarker(manager.getFocusedWindow()) || hasTextEntryMarker(manager.getActiveWindow());
    }

    private static boolean isTextEntryComponent(Component component) {
        Component current = component;
        while (current != null) {
            if (current instanceof JTextComponent && ((JTextComponent) current).isEditable()) {
                return true;
            }
            if (current instanceof TextComponent && ((TextComponent) current).isEditable()) {
                return true;
            }
            if (current instanceof JComboBox && ((JComboBox<?>) current).isEditable()) {
                return true;
            }
            if (current instanceof JTable && ((JTable) current).isEditing()) {
                return true;
            }
            if (hasTextEntryMarker(current)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private static boolean hasTextEntryMarker(Component component) {
        if (component == null) {
            return false;
        }
        String text = describeComponent(component).toLowerCase();
        return text.contains("annotation")
                || text.contains("annotat")
                || text.contains("texteditor")
                || text.contains("text editor")
                || text.contains("textinput")
                || text.contains("text input")
                || text.contains("textfield")
                || text.contains("textarea")
                || text.contains("edittext")
                || text.contains("editabletext")
                || text.contains("inputfield")
                || text.contains("noteeditor");
    }

    private static String describeComponent(Component component) {
        if (component == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(component.getClass().getName());
        String name = component.getName();
        if (name != null && !name.isEmpty()) {
            builder.append(" name=").append(name);
        }
        if (component instanceof Frame) {
            String title = ((Frame) component).getTitle();
            if (title != null && !title.isEmpty()) {
                builder.append(" title=").append(title);
            }
        }
        return builder.toString();
    }

    private static String formatPrice(double price) {
        if (!Double.isFinite(price) || price <= 0) {
            return "";
        }
        return String.format("%.2f", price);
    }

    @Override
    public ScreenSpacePainter createScreenSpacePainter(String alias, String fullName,
                                                        ScreenSpaceCanvasFactory canvasFactory) {
        CoordinateState coords = new CoordinateState();
        painterCoords.put(alias, coords);

        // Resolve the instrument by parsing the painter name we registered with
        // (Layer1ApiUserMessageModifyScreenSpacePainter.builder(..., "hoverHotkey_<symbol>")).
        // The Bookmap API exposes that name in either `alias` or `fullName` — search both.
        // Falls back to `lastRegisteredInstrument` only if parsing fails (best-effort legacy path).
        String instrument = extractInstrumentFromPainterName(alias, fullName);
        if (instrument == null) {
            instrument = lastRegisteredInstrument;
        }
        if (instrument != null) {
            painterToInstrument.put(alias, instrument);
        }
        PluginLog.info("[Rong] ScreenSpacePainter created: painterAlias=" + alias
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
                    PluginLog.info("[Rong] Coordinate mapping active for painter " + alias
                        + ": priceBottom=" + coords.priceBottom + ", priceHeight=" + coords.priceHeight
                        + ", pixelsBottom=" + coords.pixelsBottom + ", pixelsHeight=" + coords.pixelsHeight);
                }
            }

            @Override
            public void dispose() {
                painterCoords.remove(alias);
                painterToInstrument.remove(alias);
                PluginLog.info("[Rong] ScreenSpacePainter disposed for " + alias);
            }
        };
    }

    static String normalizeKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode >= KeyEvent.VK_0 && keyCode <= KeyEvent.VK_9) {
            return Integer.toString(keyCode - KeyEvent.VK_0);
        }
        if (keyCode >= KeyEvent.VK_NUMPAD0 && keyCode <= KeyEvent.VK_NUMPAD9) {
            return "numpad" + (keyCode - KeyEvent.VK_NUMPAD0);
        }
        return KeyEvent.getKeyText(keyCode).toLowerCase();
    }

    static String toViteKeyCode(String normalizedKey) {
        if (normalizedKey != null && normalizedKey.matches("numpad[0-9]")) {
            return "Numpad" + normalizedKey.charAt(normalizedKey.length() - 1);
        }
        if (normalizedKey != null && normalizedKey.length() == 1
                && normalizedKey.charAt(0) >= '0' && normalizedKey.charAt(0) <= '9') {
            return "Digit" + normalizedKey;
        }
        return "Key" + normalizedKey.toUpperCase();
    }

    static boolean isChartHotkey(String normalizedKey) {
        return CHART_HOTKEYS.contains(normalizedKey);
    }

    static String formatHoverHotkeyActionLog(
            String symbol, String keyCode, double price, boolean shiftDown) {
        return "hover_key " + symbol
                + " " + keyCode
                + " @ " + String.format(Locale.US, "%.2f", price)
                + (shiftDown ? " + shift" : "");
    }

    static boolean isAfterMarketClose(Instant instant) {
        LocalTime newYorkTime = instant.atZone(NEW_YORK_TIME_ZONE).toLocalTime();
        return !newYorkTime.isBefore(MARKET_CLOSE_TIME);
    }

    /**
     * Extract an instrument alias from the painter alias / fullName params passed to
     * createScreenSpacePainter. We register painters with name "hoverHotkey_" + symbol,
     * and Bookmap typically wraps that into something like
     * "RongPlugin#hoverHotkey_AAPL". So we look for our prefix and take
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
     * Determine which registered instrument a component belongs to by inspecting the AWT
     * hierarchy. Strategies (in order):
     *   1) Cached lookup by top-level Window
     *   2) Window title contains a known instrument alias
     *   3) Any ancestor's Component name contains a known instrument alias
     *   4) Recursive search of the window for a JLabel whose text contains a known alias
     * Returns null if nothing matches; callers should fall back to the legacy heuristic.
     */
    static String identifyInstrumentFromComponent(Component component) {
        if (component == null) return null;

        Window window = SwingUtilities.getWindowAncestor(component);
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

        // Strategy 3: walk up the component's parent chain and check each Component.getName()
        Component c = component;
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

    private static class ResolvedChartPrice {
        final String instrument;
        final double price;

        ResolvedChartPrice(String instrument, double price) {
            this.instrument = instrument;
            this.price = price;
        }
    }

    private static class HoverContext {
        final String instrument;
        final double price;
        final Component component;

        HoverContext(String instrument, double price, Component component) {
            this.instrument = instrument;
            this.price = price;
            this.component = component;
        }
    }

    /** Stores the chart coordinate mapping for one painter instance. */
    private static class CoordinateState {
        volatile long priceBottom;  // price at bottom of chart (in ticks)
        volatile long priceHeight;  // price range visible (in ticks)
        volatile int pixelsBottom;  // pixels from component bottom to heatmap bottom (bottom margin)
        volatile int pixelsHeight;  // pixel height of heatmap area

        /**
         * Compute where a mouse coordinate falls in the heatmap (0 = bottom, 1 = top).
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
