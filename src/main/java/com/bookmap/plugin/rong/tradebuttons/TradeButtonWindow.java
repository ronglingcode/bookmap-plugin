package com.bookmap.plugin.rong.tradebuttons;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

import com.bookmap.plugin.rong.PluginLog;
import com.bookmap.plugin.rong.SignalWebSocketServer;
import com.google.gson.JsonObject;

/**
 * Floating always-on-top trade button panel for a single symbol.
 */
public class TradeButtonWindow {

    private static final Color LONG_TRADEBOOK_BUTTON_COLOR = new Color(38, 139, 88);
    private static final Color SHORT_TRADEBOOK_BUTTON_COLOR = new Color(180, 62, 62);
    private static final Color TRADEBOOK_BUTTON_TEXT_COLOR = Color.WHITE;
    private static final Color HOTKEY_BUTTON_HOVER_COLOR = new Color(222, 235, 255);
    private static final int WINDOW_WIDTH = 760;
    private static final int CONTENT_WIDTH = 720;
    private static final int WALL_OUT_PAIR_INDEX = 1;
    private static final int WALL_OUT_MINIMUM_SIZE = 5_000;
    private static final double WALL_OUT_PRICE_OFFSET = 0.02;

    private final String symbol;
    private final SignalWebSocketServer server;
    private final SignalWebSocketServer.TradeButtonConfigListener buttonConfigListener;
    private JFrame frame;
    private JPanel buttonPanel;
    private volatile boolean disposed;

    public TradeButtonWindow(String symbol, SignalWebSocketServer server) {
        this.symbol = symbol;
        this.server = server;
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

        buttonPanel = new JPanel();
        buttonPanel.setBorder(new EmptyBorder(8, 8, 8, 8));
        frame.setContentPane(buttonPanel);
        renderButtons(Collections.emptyList());
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
        JPanel hotkeyPanel = new JPanel(new GridLayout(1, 5, 6, 6));
        hotkeyPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        hotkeyPanel.add(createHotkeyButton("Cancel", "cancel", "KeyC"));
        hotkeyPanel.add(createHotkeyButton("Flatten", "flatten", "KeyF"));
        hotkeyPanel.add(createHotkeyButton("Market Out 1", "market_out_1_partial", "KeyM"));
        hotkeyPanel.add(createWallOutButton());
        hotkeyPanel.add(createHotkeyButton("Swap", "swap", "KeyW"));
        return hotkeyPanel;
    }

    private JButton createHotkeyButton(String label, String id, String keyCode) {
        JButton button = new JButton(label);
        applyHotkeyButtonStyle(button);
        button.addActionListener(e -> sendHotkeyButtonMessage(id, label, keyCode));
        return button;
    }

    private JButton createWallOutButton() {
        JButton button = new JButton("Wall Out 1");
        applyHotkeyButtonStyle(button);
        button.addActionListener(e -> sendWallOutButtonMessage());
        return button;
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

        JPanel orderTypeHeaders = new JPanel(new GridLayout(1, 2, 6, 6));
        orderTypeHeaders.add(new JLabel("Breakout", SwingConstants.CENTER));
        orderTypeHeaders.add(new JLabel("Market", SwingConstants.CENTER));

        JPanel entryMethodPanel = new JPanel(new GridLayout(tradebook.getEntryMethods().size(), 2, 6, 6));
        for (String entryMethod : tradebook.getEntryMethods()) {
            entryMethodPanel.add(createButton(tradebook, entryMethod, false));
            entryMethodPanel.add(createButton(tradebook, entryMethod, true));
        }

        JPanel buttonsPanel = new JPanel(new BorderLayout(0, 4));
        buttonsPanel.add(orderTypeHeaders, BorderLayout.NORTH);
        buttonsPanel.add(entryMethodPanel, BorderLayout.CENTER);
        tradebookPanel.add(buttonsPanel, BorderLayout.CENTER);
        return tradebookPanel;
    }

    private JButton createButton(TradebookButtonGroup tradebook, String entryMethod, boolean useMarketOrder) {
        String orderType = useMarketOrder ? "Mkt" : "Breakout";
        JButton button = new JButton(entryMethod);
        applyTradebookButtonStyle(button, tradebook.getSide());
        button.addActionListener(e -> sendTradeButtonMessage(tradebook, entryMethod, useMarketOrder));
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
        server.broadcast(json.toString());
        PluginLog.action(symbol, "Button send " + orderType + " " + tradebook.getLabel() + " " + entryMethod);
        PluginLog.info("[TradeButton] " + orderType + " " + tradebook.getLabel() + ": " + entryMethod
                + " clicked for " + symbol);
    }

    private void sendHotkeyButtonMessage(String buttonId, String buttonName, String keyCode) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "custom_button_click");
        json.addProperty("symbol", symbol);
        json.addProperty("button_id", "hotkey:" + buttonId);
        json.addProperty("button_name", buttonName);
        json.addProperty("keyCode", keyCode);
        json.addProperty("key_code", keyCode);
        json.addProperty("timestamp", System.currentTimeMillis());
        server.broadcast(json.toString());
        if (!"KeyF".equals(keyCode)) {
            PluginLog.action(symbol, "Button send " + buttonName);
        }
        PluginLog.info("[TradeButton] " + buttonName + " clicked for " + symbol + " as " + keyCode);
    }

    private void sendWallOutButtonMessage() {
        SignalWebSocketServer.ExitWallAdjustment adjustment = server.resolveExitWallAdjustment(
                symbol,
                WALL_OUT_PAIR_INDEX,
                WALL_OUT_MINIMUM_SIZE,
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
        json.addProperty("minimum_wall_size", WALL_OUT_MINIMUM_SIZE);
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

    private String formatPrice(double price) {
        if (!Double.isFinite(price) || price <= 0) {
            return "";
        }
        return String.format("%.2f", price);
    }

    public void dispose() {
        disposed = true;
        server.unregisterTradeButtonConfigListener(symbol, buttonConfigListener);
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                frame.dispose();
                frame = null;
            }
            buttonPanel = null;
        });
    }
}
