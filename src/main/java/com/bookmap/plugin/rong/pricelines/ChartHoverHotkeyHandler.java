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
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

import com.bookmap.plugin.rong.ActionLogWindow;
import com.bookmap.plugin.rong.BookmapPriceNormalizer;
import com.bookmap.plugin.rong.IndicatorConfig;
import com.bookmap.plugin.rong.PluginLog;
import com.bookmap.plugin.rong.SignalWebSocketServer;
import com.bookmap.plugin.rong.SymbolUtils;
import com.bookmap.plugin.rong.tradebuttons.HotkeyButtonAction;
import com.bookmap.plugin.rong.tradebuttons.TradebookButtonGroup;
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
 * NOTE: The ScreenSpacePainterFactory creates one painter per chart. Bookmap supplies both the
 * indicator name and the alias of the chart receiving the painter. Each coordinate mapping is
 * retained with that chart alias so hover events cannot fall back to a different open symbol.
 */
public class ChartHoverHotkeyHandler implements ScreenSpacePainterFactory {

    /** Prefix used when registering this painter (see plugin initialize methods). */
    public static final String PAINTER_NAME_PREFIX = "hoverHotkey_";
    private static final ZoneId NEW_YORK_TIME_ZONE = ZoneId.of("America/New_York");
    private static final LocalTime ENTRY_HOTKEY_CUTOFF_TIME = LocalTime.of(10, 0);

    /** One coordinate state per painter instance, including its actual chart instrument. */
    private static final Set<CoordinateState> painterCoords = ConcurrentHashMap.newKeySet();

    /** Instrument alias → pips. Set by registerSymbol before painter is created. */
    private static final Map<String, Double> instrumentPips = new ConcurrentHashMap<>();

    /**
     * Cache the exact hovered component rather than its top-level window. A Bookmap window may
     * contain multiple charts, so a window-wide cache can assign one chart's symbol to all of its
     * siblings. Weak keys avoid retaining chart components after they are closed.
     */
    private static final Map<Component, String> componentToInstrument =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** Chart hotkeys forwarded to ViteApp from the currently hovered Bookmap chart. */
    private static final Set<String> CHART_HOTKEYS =
            Set.of(
                    "a", "b", "c", "f", "g", "s", "t", "w",
                    "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
                    "numpad1", "numpad2", "numpad3", "numpad4", "numpad5",
                    "numpad6", "numpad7", "numpad8", "numpad9", "numpad0");

    /** Last resolved chart hover. Button-equivalent hotkeys only require its instrument and component. */
    private static volatile HoverContext lastHoverContext;

    /** Symbol selected in Bookmap's highlighted chart tab. */
    private static volatile String highlightedInstrument;

    /** Shared AWT listener — registered once. */
    private static volatile AWTEventListener awtListener;
    private static final Object listenerLock = new Object();

    private final SignalWebSocketServer wsServer;
    private final IndicatorConfig config;

    public ChartHoverHotkeyHandler(
            SignalWebSocketServer wsServer,
            IndicatorConfig config) {
        this.wsServer = wsServer;
        this.config = config;
        ensureAwtListener();
    }

    /** Register an instrument's pips before the painter is created. */
    public void registerSymbol(String instrumentAlias, double pips) {
        instrumentPips.put(instrumentAlias, pips);
        // Bookmap can reuse component trees as symbols are opened or rearranged.
        componentToInstrument.clear();
    }

    public void unregisterSymbol(String instrumentAlias) {
        instrumentPips.remove(instrumentAlias);
        painterCoords.removeIf(coords -> instrumentAlias.equals(coords.instrument));
        synchronized (componentToInstrument) {
            componentToInstrument.entrySet().removeIf(e -> e.getValue().equals(instrumentAlias));
        }
        HoverContext hover = lastHoverContext;
        if (hover != null && instrumentAlias.equals(hover.instrument)) {
            lastHoverContext = null;
        }
        if (instrumentAlias.equals(highlightedInstrument)) {
            setHighlightedInstrument(onlyInstrument(painterInstruments()));
        }
    }

    /** Remove the global AWT listener so a fresh one can be registered on next init. */
    public static void removeAwtListener() {
        synchronized (listenerLock) {
            if (awtListener != null) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(awtListener);
                awtListener = null;
                painterCoords.clear();
                componentToInstrument.clear();
                instrumentPips.clear();
                lastHoverContext = null;
                setHighlightedInstrument(null);
            }
        }
    }

    private void ensureAwtListener() {
        if (awtListener != null) return;
        synchronized (listenerLock) {
            if (awtListener != null) return;
            awtListener = event -> {
                if (event.getID() == KeyEvent.KEY_PRESSED) {
                    KeyEvent ke = (KeyEvent) event;
                    handleChartHotkey(ke, normalizeKey(ke));
                    return;
                }

                if (event.getID() == MouseEvent.MOUSE_MOVED || event.getID() == MouseEvent.MOUSE_DRAGGED) {
                    updateHoverContext((MouseEvent) event);
                    return;
                }
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(awtListener,
                AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
        }
    }

    private void updateHoverContext(MouseEvent event) {
        Component component = event.getComponent();
        updateHighlightedInstrumentFromWindowTitle(component);
        ResolvedChartPrice price = resolveChartPrice(component, event.getY());
        if (price != null) {
            lastHoverContext = new HoverContext(price.instrument, price.price, component);
            return;
        }

        String instrument = currentHighlightedInstrument();
        if (instrument == null) {
            instrument = identifyInstrumentFromComponent(component);
        }
        lastHoverContext =
                instrument == null ? null : new HoverContext(instrument, null, component);
    }

    private void handleChartHotkey(KeyEvent event, String normalizedKey) {
        if (!isChartHotkey(normalizedKey)) {
            return;
        }
        if (config == null || !config.isEnabled(IndicatorConfig.FIRE_KEYBOARD_EVENT)) {
            return;
        }
        updateHighlightedInstrumentFromWindowTitle(event.getComponent());
        if (isTextEntryEvent(event)) {
            return;
        }

        boolean priceRequired = !isPriceIndependentHotkey(normalizedKey);
        HoverContext hover = resolveCurrentHoverContext(priceRequired);
        if (hover == null) {
            return;
        }

        String keyCode = toViteKeyCode(normalizedKey);
        if (!priceRequired) {
            sendPriceIndependentHotkey(hover.instrument, normalizedKey, keyCode);
            return;
        }

        boolean shiftDown = event.isShiftDown();
        String actionLog = formatHoverHotkeyActionLog(
                hover.instrument, keyCode, hover.price, shiftDown);
        PluginLog.action(hover.instrument, "Bookmap", actionLog);
        if (isEntryHotkeyDisabledAt(normalizedKey, Instant.now())) {
            return;
        }

        JsonObject json = new JsonObject();
        json.addProperty("type", "custom_button_click");
        BookmapPriceNormalizer.addWirePriceUnit(json);
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

        if (isWallReversalHotkey(normalizedKey)) {
            boolean bidWallReversal = "b".equals(normalizedKey);
            TradebookButtonGroup tradebook =
                    wsServer.getPrimaryWallReversalTradebook(hover.instrument, bidWallReversal);
            if (tradebook == null || tradebook.getEntryMethods().isEmpty()) {
                return;
            }

            String entryMethod = tradebook.getEntryMethods().get(0);
            json.addProperty(
                    "pattern",
                    bidWallReversal
                            ? "bookmap_bid_wall_reversal"
                            : "bookmap_offer_wall_reversal");
            json.addProperty("use_market_order", false);
            json.addProperty("order_type", "breakout");
            json.addProperty("sideIsLong", tradebook.isLong());
            json.addProperty("tradebook_id", tradebook.getTradebookId());
            json.addProperty("tradebook_name", tradebook.getTradebookName());
            json.addProperty("entry_method", entryMethod);
            wsServer.appendRegularSessionHighLow(hover.instrument, json);
        }

        wsServer.broadcast(json.toString());
    }

    private void sendPriceIndependentHotkey(
            String instrument, String normalizedKey, String keyCode) {
        String buttonId;
        String buttonName;
        if ("c".equals(normalizedKey)) {
            buttonId = "cancel";
            buttonName = "Cancel";
        } else if ("f".equals(normalizedKey)) {
            buttonId = "flatten";
            buttonName = "Flatten";
        } else if ("w".equals(normalizedKey)) {
            buttonId = "swap";
            buttonName = "Swap";
        } else {
            return;
        }

        HotkeyButtonAction.send(
                wsServer,
                instrument,
                buttonId,
                buttonName,
                keyCode,
                false);
    }

    private static HoverContext resolveCurrentHoverContext(boolean priceRequired) {
        HoverContext hover = lastHoverContext;
        if (hover == null || hover.component == null || !hover.component.isShowing()) {
            return null;
        }

        String highlighted = currentHighlightedInstrument();
        if (highlighted != null && !highlighted.equals(hover.instrument)) {
            hover = new HoverContext(highlighted, null, hover.component);
            lastHoverContext = hover;
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
            if (priceRequired) {
                lastHoverContext = null;
                return null;
            }
            return hover;
        }

        HoverContext refreshed = new HoverContext(current.instrument, current.price, hover.component);
        lastHoverContext = refreshed;
        return refreshed;
    }

    private static ResolvedChartPrice resolveChartPrice(Component comp, int localY) {
        int compHeight = (comp != null) ? comp.getHeight() : 0;
        String highlighted = currentHighlightedInstrument();
        String identified = highlighted == null ? identifyInstrumentFromComponent(comp) : null;
        String componentInstrument = resolveHoverInstrument(
                highlighted,
                identified,
                instrumentPips.keySet(),
                activePainterInstruments());
        if (componentInstrument == null) {
            // With multiple open symbols, choosing an arbitrary coordinate mapping could send an
            // order for the wrong instrument. Ambiguous hover events deliberately fail closed.
            return null;
        }

        for (CoordinateState cs : painterCoords) {
            if (cs.pixelsHeight <= 0 || cs.priceHeight <= 0) continue;

            String instrument = cs.instrument;
            if (instrument == null
                    || !componentInstrument.equals(instrument)
                    || !instrumentPips.containsKey(instrument)) {
                continue;
            }

            double pips = instrumentPips.get(instrument);
            double fraction = cs.fraction(localY, compHeight);
            if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) {
                continue;
            }

            double priceTick = cs.yToPriceTick(localY, compHeight);
            double price = BookmapPriceNormalizer.toWirePriceOrNaN(priceTick, pips);

            if (BookmapPriceNormalizer.isValidWirePrice(price)) {
                return new ResolvedChartPrice(instrument, price);
            }
        }

        return null;
    }

    /**
     * Bookmap may keep several symbols registered while creating a screen-space painter only for
     * the currently displayed chart. In that case the active painter is authoritative even though
     * the AWT component tree (for example, a tabbed chart container) mentions multiple symbols.
     */
    private static Set<String> activePainterInstruments() {
        Set<String> activeInstruments = new HashSet<>();
        for (CoordinateState coords : painterCoords) {
            if (coords.instrument != null
                    && instrumentPips.containsKey(coords.instrument)
                    && coords.pixelsHeight > 0
                    && coords.priceHeight > 0) {
                activeInstruments.add(coords.instrument);
            }
        }
        return activeInstruments;
    }

    private static Set<String> painterInstruments() {
        Set<String> instruments = new HashSet<>();
        for (CoordinateState coords : painterCoords) {
            if (coords.instrument != null && instrumentPips.containsKey(coords.instrument)) {
                instruments.add(coords.instrument);
            }
        }
        return instruments;
    }

    private static String currentHighlightedInstrument() {
        String instrument = highlightedInstrument;
        return instrument != null && instrumentPips.containsKey(instrument) ? instrument : null;
    }

    private static void setHighlightedInstrument(String instrument) {
        String next = instrument != null && instrumentPips.containsKey(instrument)
                ? instrument
                : null;
        String previous = highlightedInstrument;
        if (previous == null ? next == null : previous.equals(next)) {
            return;
        }
        highlightedInstrument = next;
        ActionLogWindow.updateHighlightedSymbol(next);
    }

    private static void updateHighlightedInstrumentFromWindowTitle(Component component) {
        String instrument = identifyInstrumentFromWindowTitle(
                component, new HashSet<>(instrumentPips.keySet()));
        if (instrument != null) {
            setHighlightedInstrument(instrument);
        }
    }

    static String identifyInstrumentFromWindowTitle(Component component, Set<String> known) {
        Component current = component;
        while (current != null && !(current instanceof Window)) {
            current = current.getParent();
        }
        if (!(current instanceof Frame)) {
            return null;
        }
        return identifyInstrumentFromTitle(((Frame) current).getTitle(), known);
    }

    static String identifyInstrumentFromTitle(String title, Set<String> known) {
        Set<String> matches = new HashSet<>();
        addKnownAliases(title, known, matches);
        return onlyInstrument(matches);
    }

    static String resolveUnidentifiedHoverInstrument(
            Set<String> registeredInstruments, Set<String> activeInstruments) {
        if (activeInstruments != null && !activeInstruments.isEmpty()) {
            return onlyInstrument(activeInstruments);
        }
        return onlyInstrument(registeredInstruments);
    }

    static String resolveHoverInstrument(
            String highlighted,
            String identifiedFromComponent,
            Set<String> registeredInstruments,
            Set<String> activeInstruments) {
        if (highlighted != null && registeredInstruments.contains(highlighted)) {
            return highlighted;
        }
        if (identifiedFromComponent != null
                && registeredInstruments.contains(identifiedFromComponent)) {
            return identifiedFromComponent;
        }
        return resolveUnidentifiedHoverInstrument(registeredInstruments, activeInstruments);
    }

    private static String onlyInstrument(Set<String> instruments) {
        if (instruments == null || instruments.size() != 1) {
            return null;
        }
        return instruments.iterator().next();
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
    public ScreenSpacePainter createScreenSpacePainter(String indicatorName, String indicatorAlias,
                                                        ScreenSpaceCanvasFactory canvasFactory) {
        // The second callback argument is the alias of the chart receiving this painter. The
        // registered indicator name is only a compatibility fallback for older callback behavior.
        String instrument = resolveInstrumentFromPainterContext(indicatorName, indicatorAlias);
        CoordinateState coords = new CoordinateState(instrument);
        painterCoords.add(coords);
        setHighlightedInstrument(instrument);

        return new ScreenSpacePainterAdapter() {
            @Override
            public void onHeatmapPriceBottom(long priceBottom) {
                coords.priceBottom = priceBottom;
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

            @Override
            public void dispose() {
                painterCoords.remove(coords);
                if (instrument != null && instrument.equals(highlightedInstrument)) {
                    setHighlightedInstrument(onlyInstrument(painterInstruments()));
                }
            }
        };
    }

    static String resolveInstrumentFromPainterContext(String indicatorName, String indicatorAlias) {
        String chartInstrument = SymbolUtils.cleanSymbol(indicatorAlias);
        if (!chartInstrument.isEmpty()) {
            return chartInstrument;
        }
        String namedInstrument = extractInstrumentFromPainterName(indicatorName, null);
        if (namedInstrument != null && !namedInstrument.isEmpty()) {
            return SymbolUtils.cleanSymbol(namedInstrument);
        }
        return onlyRegisteredInstrument();
    }

    private static String onlyRegisteredInstrument() {
        return onlyInstrument(instrumentPips.keySet());
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

    static boolean isPriceIndependentHotkey(String normalizedKey) {
        return "c".equals(normalizedKey)
                || "f".equals(normalizedKey)
                || "w".equals(normalizedKey);
    }

    static boolean isWallReversalHotkey(String normalizedKey) {
        return "b".equals(normalizedKey) || "s".equals(normalizedKey);
    }

    static String formatHoverHotkeyActionLog(
            String symbol, String keyCode, double price, boolean shiftDown) {
        return "hover_key " + symbol
                + " " + keyCode
                + " @ " + String.format(Locale.US, "%.2f", price)
                + (shiftDown ? " + shift" : "");
    }

    static boolean isEntryHotkeyDisabledAt(String normalizedKey, Instant instant) {
        if (!isWallReversalHotkey(normalizedKey)) {
            return false;
        }
        LocalTime newYorkTime = instant.atZone(NEW_YORK_TIME_ZONE).toLocalTime();
        return !newYorkTime.isBefore(ENTRY_HOTKEY_CUTOFF_TIME);
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
     * Determine which registered instrument a component belongs to by inspecting its AWT branch.
     * The nearest ancestor subtree containing exactly one known symbol wins. Once traversal reaches
     * a container shared by multiple charts, the result remains ambiguous and no symbol is chosen.
     */
    static String identifyInstrumentFromComponent(Component component) {
        if (component == null) return null;

        String cached = componentToInstrument.get(component);
        if (cached != null && instrumentPips.containsKey(cached)) {
            return cached;
        }

        String instrument = identifyInstrumentFromComponent(
                component, new HashSet<>(instrumentPips.keySet()));
        if (instrument != null) {
            componentToInstrument.put(component, instrument);
        }
        return instrument;
    }

    static String identifyInstrumentFromComponent(Component component, Set<String> known) {
        if (component == null || known == null || known.isEmpty()) return null;

        Component current = component;
        while (current != null) {
            // Component names near the event source are stronger evidence than labels elsewhere.
            // A top-level window is excluded because it can contain several instrument charts.
            if (!(current instanceof Window)) {
                Set<String> directMatches = new HashSet<>();
                collectDirectKnownAliases(current, known, directMatches);
                if (directMatches.size() == 1) {
                    return directMatches.iterator().next();
                }
            }

            Set<String> subtreeMatches = new HashSet<>();
            collectKnownAliases(current, known, subtreeMatches);
            if (subtreeMatches.size() == 1) {
                return subtreeMatches.iterator().next();
            }
            current = current.getParent();
        }

        return null;
    }

    private static void collectKnownAliases(
            Component root, Set<String> known, Set<String> matches) {
        if (root == null) return;
        collectDirectKnownAliases(root, known, matches);
        if (root instanceof Container) {
            for (Component child : ((Container) root).getComponents()) {
                collectKnownAliases(child, known, matches);
            }
        }
    }

    private static void collectDirectKnownAliases(
            Component component, Set<String> known, Set<String> matches) {
        addKnownAliases(component.getName(), known, matches);
        if (component instanceof JLabel) {
            addKnownAliases(((JLabel) component).getText(), known, matches);
        }
        if (component instanceof Frame) {
            addKnownAliases(((Frame) component).getTitle(), known, matches);
        }
    }

    private static void addKnownAliases(String text, Set<String> known, Set<String> matches) {
        if (text == null || text.isEmpty()) return;
        for (String alias : known) {
            if (alias != null && !alias.isEmpty() && text.contains(alias)) {
                matches.add(alias);
            }
        }
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
        final Double price;
        final Component component;

        HoverContext(String instrument, Double price, Component component) {
            this.instrument = instrument;
            this.price = price;
            this.component = component;
        }
    }

    /** Stores the chart coordinate mapping for one painter instance. */
    private static class CoordinateState {
        final String instrument;
        volatile long priceBottom;  // price at bottom of chart (in ticks)
        volatile long priceHeight;  // price range visible (in ticks)
        volatile int pixelsBottom;  // pixels from component bottom to heatmap bottom (bottom margin)
        volatile int pixelsHeight;  // pixel height of heatmap area

        CoordinateState(String instrument) {
            this.instrument = instrument;
        }

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
