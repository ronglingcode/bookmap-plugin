package com.bookmap.plugin.common;

import java.awt.GridLayout;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * Floating always-on-top trade button panel for a single symbol.
 */
public class TradeButtonWindow {

    private final String symbol;
    private final SignalWebSocketServer server;
    private JFrame frame;

    public TradeButtonWindow(String symbol, SignalWebSocketServer server) {
        this.symbol = symbol;
        this.server = server;
        SwingUtilities.invokeLater(this::buildWindow);
    }

    private void buildWindow() {
        frame = new JFrame("Trade: " + symbol);
        frame.setAlwaysOnTop(true);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setResizable(false);

        JPanel buttonPanel = new JPanel(new GridLayout(1, 2, 6, 6));
        buttonPanel.setBorder(new EmptyBorder(8, 8, 8, 8));
        buttonPanel.add(createButton("Buy", "buy"));
        buttonPanel.add(createButton("Sell", "sell"));

        frame.setContentPane(buttonPanel);
        frame.pack();
        frame.setVisible(true);
    }

    private JButton createButton(String label, String action) {
        JButton button = new JButton(label);
        button.addActionListener(e -> sendTradeButtonMessage(action));
        return button;
    }

    private void sendTradeButtonMessage(String action) {
        String json = String.format(
                "{\"type\":\"custom_button_click\",\"symbol\":\"%s\",\"button_name\":\"%s\",\"action\":\"%s\",\"side\":\"%s\",\"timestamp\":%d}",
                escapeJson(symbol), action, action, action, System.currentTimeMillis());
        server.broadcast(json);
        PluginLog.info("[TradeButton] " + action + " clicked for " + symbol);
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public void dispose() {
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                frame.dispose();
                frame = null;
            }
        });
    }
}
