package com.bookmap.plugin.rong.tradebuttons;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
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

    private final String symbol;
    private final SignalWebSocketServer server;
    private final SignalWebSocketServer.TradeButtonConfigListener buttonConfigListener;
    private JFrame frame;
    private JPanel buttonPanel;

    public TradeButtonWindow(String symbol, SignalWebSocketServer server) {
        this.symbol = symbol;
        this.server = server;
        this.buttonConfigListener = this::setButtons;
        SwingUtilities.invokeLater(this::buildWindow);
    }

    private void buildWindow() {
        frame = new JFrame("Trade: " + symbol);
        frame.setAlwaysOnTop(true);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setResizable(false);

        buttonPanel = new JPanel();
        buttonPanel.setBorder(new EmptyBorder(8, 8, 8, 8));
        frame.setContentPane(buttonPanel);
        renderButtons(Collections.emptyList());
        frame.pack();
        frame.setVisible(true);

        server.registerTradeButtonConfigListener(symbol, buttonConfigListener);
    }

    private void setButtons(List<TradebookButtonGroup> tradebooks) {
        SwingUtilities.invokeLater(() -> renderButtons(tradebooks));
    }

    private void renderButtons(List<TradebookButtonGroup> tradebooks) {
        if (buttonPanel == null) {
            return;
        }
        buttonPanel.removeAll();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
        buttonPanel.add(createHotkeyPanel());

        boolean hasTradebookButtons = false;
        if (tradebooks != null && !tradebooks.isEmpty()) {
            for (TradebookButtonGroup tradebook : tradebooks) {
                if (!tradebook.getEntryMethods().isEmpty()) {
                    buttonPanel.add(createTradebookPanel(tradebook));
                    hasTradebookButtons = true;
                }
            }
        }
        if (!hasTradebookButtons) {
            buttonPanel.add(new JLabel("Waiting for buttons", SwingConstants.CENTER));
        }
        buttonPanel.revalidate();
        buttonPanel.repaint();
        if (frame != null) {
            frame.pack();
        }
    }

    private JPanel createHotkeyPanel() {
        JPanel hotkeyPanel = new JPanel(new GridLayout(1, 3, 6, 6));
        hotkeyPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        hotkeyPanel.add(createHotkeyButton("Cancel", "cancel", "KeyC"));
        hotkeyPanel.add(createHotkeyButton("Flatten", "flatten", "KeyF"));
        hotkeyPanel.add(createHotkeyButton("Market Out 1", "market_out_1_partial", "KeyM"));
        return hotkeyPanel;
    }

    private JButton createHotkeyButton(String label, String id, String keyCode) {
        JButton button = new JButton(label);
        button.addActionListener(e -> sendHotkeyButtonMessage(id, label, keyCode));
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

    public void dispose() {
        server.unregisterTradeButtonConfigListener(symbol, buttonConfigListener);
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                frame.dispose();
                frame = null;
            }
        });
    }
}
