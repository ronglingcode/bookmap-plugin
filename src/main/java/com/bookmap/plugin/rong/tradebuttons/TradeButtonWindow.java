package com.bookmap.plugin.rong.tradebuttons;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

import com.bookmap.plugin.rong.PluginLog;
import com.bookmap.plugin.rong.SignalWebSocketServer;
import com.bookmap.plugin.rong.WallThresholdConfig;
import com.google.gson.JsonObject;

/**
 * Floating always-on-top trade button panel for a single symbol.
 */
public class TradeButtonWindow {

    private static final Color LONG_TRADEBOOK_BUTTON_COLOR = new Color(38, 139, 88);
    private static final Color SHORT_TRADEBOOK_BUTTON_COLOR = new Color(180, 62, 62);
    private static final Color TRADEBOOK_BUTTON_TEXT_COLOR = Color.WHITE;
    private static final Color HOTKEY_BUTTON_HOVER_COLOR = new Color(222, 235, 255);
    private static final Color MODE_BREAKOUT_BACKGROUND = new Color(38, 139, 88);
    private static final Color MODE_MARKET_BACKGROUND = new Color(190, 121, 28);
    private static final Color MODE_TEXT_COLOR = Color.WHITE;
    private static final Color THRESHOLD_BACKGROUND = new Color(232, 238, 246);
    private static final Color THRESHOLD_TEXT_COLOR = new Color(24, 35, 46);
    private static final int WINDOW_WIDTH = 570;
    private static final int CONTENT_WIDTH = 540;
    private static final int WALL_OUT_PAIR_INDEX = 1;
    private static final int WALL_OUT_PROTECTED_ABSOLUTE_LEVELS = 2;
    private static final double WALL_OUT_PRICE_OFFSET = 0.02;
    private static final int WALL_THRESHOLD_REFRESH_MS = 1_000;
    private static final String SHIFT_DOWN_CLIENT_PROPERTY = "rong.shiftDownForClick";
    private static final Object SHIFT_LISTENER_LOCK = new Object();
    private static final Set<TradeButtonWindow> OPEN_WINDOWS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<String> PRESSED_SHIFT_KEYS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static volatile AWTEventListener shiftStateListener;
    private static volatile boolean shiftPressed;

    private final String symbol;
    private final SignalWebSocketServer server;
    private final IntSupplier wallThresholdFloorSupplier;
    private final SignalWebSocketServer.TradeButtonConfigListener buttonConfigListener;
    private JFrame frame;
    private JPanel buttonPanel;
    private JLabel shiftModeLabel;
    private JLabel wallThresholdLabel;
    private Timer wallThresholdTimer;
    private volatile boolean disposed;

    public TradeButtonWindow(String symbol, SignalWebSocketServer server,
                             IntSupplier wallThresholdFloorSupplier) {
        this.symbol = symbol;
        this.server = server;
        this.wallThresholdFloorSupplier = wallThresholdFloorSupplier == null
                ? () -> WallThresholdConfig.DEFAULT_THRESHOLD_FLOOR
                : wallThresholdFloorSupplier;
        this.buttonConfigListener = this::setButtons;
        SwingUtilities.invokeLater(this::buildWindow);
    }

    private void buildWindow() {
        if (disposed) {
            return;
        }
        frame = new JFrame("Trade: " + symbol);
        frame.setAlwaysOnTop(true);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setResizable(false);
        frame.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                updateModeLabel(shiftPressed);
            }

            @Override
            public void windowLostFocus(WindowEvent e) {
                clearShiftState();
            }
        });

        buttonPanel = new JPanel();
        buttonPanel.setBorder(new EmptyBorder(8, 8, 8, 8));
        frame.setContentPane(buttonPanel);
        registerShiftTracker(this);
        renderButtons(Collections.emptyList());
        startWallThresholdTimer();
        frame.setVisible(true);

        server.registerTradeButtonConfigListener(symbol, buttonConfigListener);
    }

    private void setButtons(List<TradebookButtonGroup> tradebooks) {
        SwingUtilities.invokeLater(() -> {
            if (!disposed) {
                renderButtons(tradebooks);
            }
        });
    }

    private void renderButtons(List<TradebookButtonGroup> tradebooks) {
        if (disposed || buttonPanel == null) {
            return;
        }
        buttonPanel.removeAll();
        buttonPanel.setPreferredSize(null);
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
        addFullWidth(createModePanel());
        addFullWidth(createHotkeyPanel());

        boolean hasTradebookButtons = false;
        JPanel tradebookGrid = new JPanel(new GridLayout(0, 2, 6, 6));
        if (tradebooks != null && !tradebooks.isEmpty()) {
            for (TradebookButtonGroup tradebook : tradebooks) {
                if (!tradebook.getEntryMethods().isEmpty()) {
                    tradebookGrid.add(createTradebookPanel(tradebook));
                    hasTradebookButtons = true;
                }
            }
        }
        if (hasTradebookButtons) {
            addFullWidth(tradebookGrid);
        }
        if (!hasTradebookButtons) {
            JLabel waitingLabel = new JLabel("Waiting for buttons", SwingConstants.CENTER);
            waitingLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            buttonPanel.add(waitingLabel);
        }
        buttonPanel.setPreferredSize(new Dimension(CONTENT_WIDTH, buttonPanel.getPreferredSize().height));
        buttonPanel.revalidate();
        buttonPanel.repaint();
        if (frame != null) {
            frame.pack();
            frame.setSize(WINDOW_WIDTH, frame.getHeight());
        }
    }

    private void addFullWidth(JPanel panel) {
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        buttonPanel.add(panel);
    }

    private JPanel createHotkeyPanel() {
        JPanel hotkeyPanel = new JPanel(new GridLayout(0, 4, 6, 6));
        hotkeyPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        hotkeyPanel.add(createHotkeyButton("Cancel", "cancel", "KeyC"));
        hotkeyPanel.add(createHotkeyButton("Flatten", "flatten", "KeyF"));
        hotkeyPanel.add(createHotkeyButton("Add Partial", "add_partial", "KeyA", true));
        hotkeyPanel.add(createHotkeyButton("Market Out 1", "market_out_1_partial", "KeyM"));
        hotkeyPanel.add(createHotkeyButton("Market Out Half", "market_out_half", "KeyG", true));
        hotkeyPanel.add(createWallOutButton());
        hotkeyPanel.add(createHotkeyButton("Swap", "swap", "KeyW"));
        return hotkeyPanel;
    }

    private JButton createHotkeyButton(String label, String id, String keyCode) {
        return createHotkeyButton(label, id, keyCode, false);
    }

    private JButton createHotkeyButton(String label, String id, String keyCode, boolean shiftKey) {
        JButton button = new JButton(label);
        applyHotkeyButtonStyle(button);
        button.addActionListener(e -> sendHotkeyButtonMessage(id, label, keyCode, shiftKey));
        return button;
    }

    private JButton createWallOutButton() {
        JButton button = new JButton("Wall Out 1");
        applyHotkeyButtonStyle(button);
        button.addActionListener(e -> sendWallOutButtonMessage());
        return button;
    }

    private JPanel createModePanel() {
        JPanel modePanel = new JPanel(new GridLayout(0, 1, 0, 4));
        modePanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        shiftModeLabel = new JLabel("", SwingConstants.CENTER);
        shiftModeLabel.setOpaque(true);
        shiftModeLabel.setForeground(MODE_TEXT_COLOR);
        shiftModeLabel.setFont(shiftModeLabel.getFont().deriveFont(Font.BOLD));
        shiftModeLabel.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
        updateModeLabel(shiftPressed);

        wallThresholdLabel = new JLabel("", SwingConstants.CENTER);
        wallThresholdLabel.setOpaque(true);
        wallThresholdLabel.setForeground(THRESHOLD_TEXT_COLOR);
        wallThresholdLabel.setBackground(THRESHOLD_BACKGROUND);
        wallThresholdLabel.setFont(wallThresholdLabel.getFont().deriveFont(Font.BOLD, 12f));
        wallThresholdLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        updateWallThresholdLabel();

        modePanel.add(shiftModeLabel);
        modePanel.add(wallThresholdLabel);
        attachShiftMouseRefresh(modePanel);
        attachShiftMouseRefresh(shiftModeLabel);
        attachShiftMouseRefresh(wallThresholdLabel);
        return modePanel;
    }

    private JPanel createTradebookPanel(TradebookButtonGroup tradebook) {
        JPanel tradebookPanel = new JPanel(new BorderLayout(0, 6));
        tradebookPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0, 0, 8, 0),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createEtchedBorder(),
                        BorderFactory.createEmptyBorder(6, 6, 6, 6))));

        JLabel tradebookLabel = new JLabel(tradebook.getLabel());
        tradebookPanel.add(tradebookLabel, BorderLayout.NORTH);

        JPanel entryMethodPanel = new JPanel(new GridLayout(tradebook.getEntryMethods().size(), 1, 6, 6));
        for (String entryMethod : tradebook.getEntryMethods()) {
            entryMethodPanel.add(createEntryButton(tradebook, entryMethod));
        }
        tradebookPanel.add(entryMethodPanel, BorderLayout.CENTER);
        return tradebookPanel;
    }

    private JButton createEntryButton(TradebookButtonGroup tradebook, String entryMethod) {
        JButton button = new JButton(entryMethod);
        applyTradebookButtonStyle(button, tradebook.getSide());
        button.putClientProperty(SHIFT_DOWN_CLIENT_PROPERTY, Boolean.FALSE);
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (button.getModel().isPressed()) {
                    boolean shiftDown = isShiftModified(e);
                    button.putClientProperty(SHIFT_DOWN_CLIENT_PROPERTY, shiftDown);
                    setShiftPressed(shiftDown);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                boolean shiftDown = isShiftModified(e);
                button.putClientProperty(SHIFT_DOWN_CLIENT_PROPERTY, shiftDown);
                setShiftPressed(shiftDown);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                setShiftPressed(isShiftModified(e));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                button.putClientProperty(SHIFT_DOWN_CLIENT_PROPERTY, Boolean.FALSE);
                setShiftPressed(isShiftModified(e));
            }
        });
        button.addActionListener(e -> {
            boolean useMarketOrder = isMarketOrderAction(e, button);
            button.putClientProperty(SHIFT_DOWN_CLIENT_PROPERTY, Boolean.FALSE);
            setShiftPressed(useMarketOrder);
            sendTradeButtonMessage(tradebook, entryMethod, useMarketOrder);
        });
        return button;
    }

    private void applyTradebookButtonStyle(JButton button, String side) {
        Color color = getTradebookButtonColor(side);
        if (color == null) {
            return;
        }
        button.setBackground(color);
        button.setForeground(TRADEBOOK_BUTTON_TEXT_COLOR);
        button.setOpaque(true);
        button.setBorderPainted(false);
        applyButtonHoverStyle(button, color, brighten(color));
    }

    private void applyHotkeyButtonStyle(JButton button) {
        applyButtonHoverStyle(button, button.getBackground(), HOTKEY_BUTTON_HOVER_COLOR);
    }

    private void applyButtonHoverStyle(JButton button, Color baseColor, Color hoverColor) {
        button.setBackground(baseColor);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setRolloverEnabled(true);
        attachShiftMouseRefresh(button);
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (button.isEnabled()) {
                    button.setBackground(hoverColor);
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(baseColor);
            }
        });
    }

    private void attachShiftMouseRefresh(Component component) {
        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                setShiftPressed(isShiftModified(e));
            }

            @Override
            public void mousePressed(MouseEvent e) {
                setShiftPressed(isShiftModified(e));
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                setShiftPressed(isShiftModified(e));
            }
        });
    }

    private Color brighten(Color color) {
        return new Color(
                brightenChannel(color.getRed()),
                brightenChannel(color.getGreen()),
                brightenChannel(color.getBlue()));
    }

    private int brightenChannel(int value) {
        return Math.min(255, value + 38);
    }

    private Color getTradebookButtonColor(String side) {
        if (side == null) {
            return null;
        }
        String normalizedSide = side.trim();
        if ("long".equalsIgnoreCase(normalizedSide) || "buy".equalsIgnoreCase(normalizedSide)) {
            return LONG_TRADEBOOK_BUTTON_COLOR;
        }
        if ("short".equalsIgnoreCase(normalizedSide) || "sell".equalsIgnoreCase(normalizedSide)) {
            return SHORT_TRADEBOOK_BUTTON_COLOR;
        }
        return null;
    }

    static boolean isShiftModified(ActionEvent event) {
        if (event == null) {
            return false;
        }
        int modifiers = event.getModifiers();
        return (modifiers & ActionEvent.SHIFT_MASK) != 0
                || (modifiers & InputEvent.SHIFT_DOWN_MASK) != 0;
    }

    private static boolean isShiftModified(MouseEvent event) {
        return event != null && event.isShiftDown();
    }

    private static boolean isMarketOrderAction(ActionEvent event, JButton button) {
        return isShiftModified(event)
                || Boolean.TRUE.equals(button.getClientProperty(SHIFT_DOWN_CLIENT_PROPERTY));
    }

    private void updateModeLabel(boolean marketMode) {
        if (shiftModeLabel == null) {
            return;
        }
        shiftModeLabel.setText(marketMode
                ? "[" + symbol + "] Mode: MARKET - Shift pressed"
                : "[" + symbol + "] Mode: BREAKOUT - Shift not pressed");
        shiftModeLabel.setBackground(marketMode ? MODE_MARKET_BACKGROUND : MODE_BREAKOUT_BACKGROUND);
    }

    private void startWallThresholdTimer() {
        if (wallThresholdTimer != null) {
            return;
        }
        wallThresholdTimer = new Timer(WALL_THRESHOLD_REFRESH_MS, e -> updateWallThresholdLabel());
        wallThresholdTimer.setRepeats(true);
        wallThresholdTimer.start();
        updateWallThresholdLabel();
    }

    private void updateWallThresholdLabel() {
        if (wallThresholdLabel == null) {
            return;
        }
        int minimumWallSize = getWallThresholdFloor();
        SignalWebSocketServer.OrderbookWallThreshold threshold =
                server.getOrderbookWallThreshold(symbol, minimumWallSize);
        if (!threshold.isAvailable()) {
            wallThresholdLabel.setText("Wall threshold: waiting for book");
            return;
        }

        String percentileSize = threshold.getPercentileMinSize() > 0
                ? formatShareSize(threshold.getPercentileMinSize())
                : "n/a";
        wallThresholdLabel.setText("Wall threshold: " + formatShareSize(threshold.getEffectiveMinSize())
                + " (P" + formatPercentile(threshold.getPercentile())
                + "=" + percentileSize
                + ", floor=" + formatShareSize(threshold.getAbsoluteMinSize()) + ")");
    }

    private static void registerShiftTracker(TradeButtonWindow window) {
        OPEN_WINDOWS.add(window);
        ensureShiftStateListener();
    }

    private static void unregisterShiftTracker(TradeButtonWindow window) {
        OPEN_WINDOWS.remove(window);
        if (!OPEN_WINDOWS.isEmpty()) {
            return;
        }
        synchronized (SHIFT_LISTENER_LOCK) {
            if (!OPEN_WINDOWS.isEmpty()) {
                return;
            }
            if (shiftStateListener != null) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(shiftStateListener);
                shiftStateListener = null;
            }
            PRESSED_SHIFT_KEYS.clear();
            shiftPressed = false;
        }
    }

    private static void ensureShiftStateListener() {
        if (shiftStateListener != null) {
            return;
        }
        synchronized (SHIFT_LISTENER_LOCK) {
            if (shiftStateListener != null) {
                return;
            }
            shiftStateListener = event -> {
                if (!(event instanceof KeyEvent)) {
                    return;
                }
                KeyEvent keyEvent = (KeyEvent) event;
                if (keyEvent.getKeyCode() != KeyEvent.VK_SHIFT) {
                    return;
                }
                if (keyEvent.getID() == KeyEvent.KEY_PRESSED) {
                    PRESSED_SHIFT_KEYS.add(shiftKeyId(keyEvent));
                    setShiftPressed(true);
                } else if (keyEvent.getID() == KeyEvent.KEY_RELEASED) {
                    PRESSED_SHIFT_KEYS.remove(shiftKeyId(keyEvent));
                    setShiftPressed(!PRESSED_SHIFT_KEYS.isEmpty());
                }
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(shiftStateListener, AWTEvent.KEY_EVENT_MASK);
        }
    }

    private static String shiftKeyId(KeyEvent event) {
        return event.getKeyCode() + ":" + event.getKeyLocation();
    }

    private static void clearShiftState() {
        PRESSED_SHIFT_KEYS.clear();
        setShiftPressed(false);
    }

    private static void setShiftPressed(boolean pressed) {
        shiftPressed = pressed;
        SwingUtilities.invokeLater(() -> {
            for (TradeButtonWindow window : OPEN_WINDOWS) {
                if (!window.disposed) {
                    window.updateModeLabel(pressed);
                }
            }
        });
    }

    private void sendTradeButtonMessage(TradebookButtonGroup tradebook, String entryMethod, boolean useMarketOrder) {
        String orderType = useMarketOrder ? "market" : "breakout";
        JsonObject json = new JsonObject();
        json.addProperty("type", "custom_button_click");
        json.addProperty("symbol", symbol);
        json.addProperty("button_id", tradebook.getId() + ":" + entryMethod + ":" + orderType);
        json.addProperty("button_name", (useMarketOrder ? "Mkt: " : "Breakout: ") + tradebook.getLabel() + ": " + entryMethod);
        json.addProperty("use_market_order", useMarketOrder);
        json.addProperty("order_type", orderType);
        json.addProperty("side", tradebook.getSide());
        json.addProperty("tradebook_id", tradebook.getTradebookId());
        json.addProperty("tradebook_name", tradebook.getTradebookName());
        json.addProperty("entry_method", entryMethod);
        json.addProperty("timestamp", System.currentTimeMillis());
        int minimumWallSize = getWallThresholdFloor();
        server.appendOrderbookSnapshot(
                symbol, json, minimumWallSize, WALL_OUT_PROTECTED_ABSOLUTE_LEVELS);
        server.broadcast(json.toString());
        PluginLog.action(symbol, "Button send " + orderType + " " + tradebook.getLabel() + " " + entryMethod);
        PluginLog.info("[TradeButton] " + orderType + " " + tradebook.getLabel() + ": " + entryMethod
                + " clicked for " + symbol);
    }

    private void sendHotkeyButtonMessage(String buttonId, String buttonName, String keyCode, boolean shiftKey) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "custom_button_click");
        json.addProperty("symbol", symbol);
        json.addProperty("button_id", "hotkey:" + buttonId);
        json.addProperty("button_name", buttonName);
        json.addProperty("keyCode", keyCode);
        json.addProperty("key_code", keyCode);
        json.addProperty("shiftKey", shiftKey);
        json.addProperty("shift_key", shiftKey);
        json.addProperty("timestamp", System.currentTimeMillis());
        server.broadcast(json.toString());
        if (!"KeyF".equals(keyCode)) {
            PluginLog.action(symbol, "Button send " + buttonName);
        }
        PluginLog.info("[TradeButton] " + buttonName + " clicked for " + symbol + " as "
                + (shiftKey ? "Shift+" : "") + keyCode);
    }

    private void sendWallOutButtonMessage() {
        int minimumWallSize = getWallThresholdFloor();
        SignalWebSocketServer.ExitWallAdjustment adjustment = server.resolveExitWallAdjustment(
                symbol,
                WALL_OUT_PAIR_INDEX,
                minimumWallSize,
                WALL_OUT_PRICE_OFFSET);
        if (!adjustment.isAvailable()) {
            PluginLog.action(symbol, "Wall Out 1 blocked: " + adjustment.getReason());
            PluginLog.info("[TradeButton] Wall Out 1 blocked for " + symbol + ": " + adjustment.getReason());
            return;
        }

        JsonObject json = new JsonObject();
        json.addProperty("type", "custom_button_click");
        json.addProperty("symbol", symbol);
        json.addProperty("button_id", "hotkey:wall_out_1");
        json.addProperty("button_name", "Wall Out 1");
        json.addProperty("action", "adjust_exit_limit_to_bookmap_wall");
        json.addProperty("pair_index", adjustment.getPairIndex());
        json.addProperty("order_role", "LIMIT");
        json.addProperty("limit_order_id", adjustment.getLimitOrderId());
        json.addProperty("parent_order_id", adjustment.getParentOrderId());
        json.addProperty("limit_order_quantity", adjustment.getLimitOrderQuantity());
        json.addProperty("current_limit_price", adjustment.getCurrentLimitPrice());
        json.addProperty("position_side", adjustment.isLongPosition() ? "long" : "short");
        json.addProperty("exit_side", adjustment.isLongPosition() ? "sell" : "buy");
        json.addProperty("wall_side", adjustment.isBidWall() ? "bid" : "ask");
        json.addProperty("wall_price_tick", adjustment.getWallPriceTick());
        json.addProperty("wall_price", adjustment.getWallPrice());
        json.addProperty("wall_size", adjustment.getWallSize());
        json.addProperty("minimum_wall_size", minimumWallSize);
        json.addProperty("offset", adjustment.getOffset());
        json.addProperty("target_price", adjustment.getTargetPrice());
        json.addProperty("price", adjustment.getTargetPrice());
        json.addProperty("source", adjustment.getSource());
        json.addProperty("timestamp", System.currentTimeMillis());
        server.broadcast(json.toString());

        PluginLog.action(symbol, "Button send Wall Out 1 @ " + formatPrice(adjustment.getTargetPrice())
                + " before " + (adjustment.isBidWall() ? "bid" : "ask") + " wall "
                + formatPrice(adjustment.getWallPrice()));
        PluginLog.info("[TradeButton] Wall Out 1 clicked for " + symbol
                + ": target=" + formatPrice(adjustment.getTargetPrice())
                + ", wall=" + formatPrice(adjustment.getWallPrice())
                + ", size=" + adjustment.getWallSize());
    }

    private int getWallThresholdFloor() {
        try {
            return Math.max(0, wallThresholdFloorSupplier.getAsInt());
        } catch (RuntimeException e) {
            PluginLog.error("[TradeButton] Failed to read wall threshold floor for "
                    + symbol + ": " + e.getMessage());
            return WallThresholdConfig.DEFAULT_THRESHOLD_FLOOR;
        }
    }

    private String formatPrice(double price) {
        if (!Double.isFinite(price) || price <= 0) {
            return "";
        }
        return String.format("%.2f", price);
    }

    private static String formatShareSize(int size) {
        if (size >= 1_000_000) {
            return formatCompactSize(size, 1_000_000, "M");
        }
        if (size >= 1_000) {
            return formatCompactSize(size, 1_000, "K");
        }
        return Integer.toString(size);
    }

    private static String formatCompactSize(int size, int unit, String suffix) {
        if (size % unit == 0) {
            return (size / unit) + suffix;
        }
        return String.format(Locale.US, "%.1f%s", size / (double) unit, suffix);
    }

    private static String formatPercentile(double percentile) {
        if (Math.abs(percentile - Math.rint(percentile)) < 0.00001) {
            return String.format(Locale.US, "%.0f", percentile);
        }
        return String.format(Locale.US, "%.1f", percentile);
    }

    public void dispose() {
        disposed = true;
        if (wallThresholdTimer != null) {
            wallThresholdTimer.stop();
            wallThresholdTimer = null;
        }
        unregisterShiftTracker(this);
        server.unregisterTradeButtonConfigListener(symbol, buttonConfigListener);
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                frame.dispose();
                frame = null;
            }
            buttonPanel = null;
            shiftModeLabel = null;
            wallThresholdLabel = null;
        });
    }
}
