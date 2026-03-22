package com.bookmap.plugin.common;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * Floating always-on-top window with trade buttons for a single symbol.
 * Button clicks broadcast custom_button_click events via WebSocket.
 */
public class TradeButtonWindow {

    private final String symbol;
    private final SignalWebSocketServer server;
    private final PriceLineStore store;
    private final PriceLineStore.ChangeListener storeListener;
    private JFrame frame;
    private JLabel infoLabel;

    public TradeButtonWindow(String symbol, SignalWebSocketServer server, PriceLineStore store) {
        this.symbol = symbol;
        this.server = server;
        this.store = store;

        this.storeListener = instrumentAlias -> {
            if (symbol.equals(instrumentAlias)) {
                SwingUtilities.invokeLater(this::updateLabel);
            }
        };
        store.addListener(storeListener);

        SwingUtilities.invokeLater(this::buildWindow);
    }

    private void buildWindow() {
        frame = new JFrame("Trade: " + symbol);
        frame.setAlwaysOnTop(true);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setResizable(true);

        JPanel content = new JPanel(new BorderLayout(5, 5));
        content.setBorder(new EmptyBorder(8, 8, 8, 8));

        infoLabel = new JLabel(buildLabelText());
        content.add(infoLabel, BorderLayout.NORTH);

        JPanel buttonPanel = new JPanel(new GridLayout(2, 2, 5, 5));
        buttonPanel.add(createButton("Buy Market", "buy_market"));
        buttonPanel.add(createButton("Buy Stop", "buy_stop"));
        buttonPanel.add(createButton("Sell Market", "sell_market"));
        buttonPanel.add(createButton("Sell Stop", "sell_stop"));
        content.add(buttonPanel, BorderLayout.CENTER);

        frame.setContentPane(content);
        frame.pack();
        frame.setVisible(true);
    }

    private JButton createButton(String label, String buttonName) {
        JButton button = new JButton(label);
        button.addActionListener(e -> {
            String json = String.format(
                "{\"type\":\"custom_button_click\",\"symbol\":\"%s\",\"button_name\":\"%s\",\"timestamp\":%d}",
                symbol, buttonName, System.currentTimeMillis());
            server.broadcast(json);
            PluginLog.info("[TradeButton] " + buttonName + " clicked for " + symbol);
        });
        return button;
    }

    private void updateLabel() {
        if (infoLabel != null) {
            infoLabel.setText(buildLabelText());
        }
    }

    private String buildLabelText() {
        String stopText = "N/A";
        List<PriceLine> lines = store.getLines(symbol);
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).getType() == PriceLine.LineType.STOP_LOSS) {
                stopText = String.format("%.2f", lines.get(i).getRealPrice());
                break;
            }
        }
        return "symbol: " + symbol + ", stop loss: " + stopText;
    }

    public void dispose() {
        store.removeListener(storeListener);
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                frame.dispose();
            }
        });
    }
}
