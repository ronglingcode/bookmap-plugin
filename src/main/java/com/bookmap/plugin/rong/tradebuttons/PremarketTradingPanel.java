package com.bookmap.plugin.rong.tradebuttons;

import com.bookmap.plugin.rong.SignalWebSocketServer;
import com.bookmap.plugin.rong.AccountStateDefinition;
import com.bookmap.plugin.rong.VerticalWindowLayout;
import com.bookmap.plugin.rong.PluginLog;
import com.bookmap.plugin.rong.miniviteapp.core.marketdata.MarketClock;
import com.google.gson.JsonObject;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Manual premarket ticket, deliberately independent of all normal entry-button gestures. */
public final class PremarketTradingPanel extends JPanel implements AutoCloseable {
    private final String symbol;
    private final SignalWebSocketServer server;
    private final JTextField quantity = new JTextField("", 7);
    private final JTextField offset = new JTextField("1", 5);
    private final JTextField limitPrice = new JTextField("", 7);
    private final JTextField stopPrice = new JTextField("", 7);
    private final JLabel stopStatus = new JLabel();
    private final JButton setStop = new JButton("Set Stop");
    private final JLabel account = new JLabel("Position: waiting for broker");
    private final JLabel status = new JLabel();
    private final List<JButton> buttons = new ArrayList<>();
    private final SignalWebSocketServer.AccountStateListener accountListener = this::updateAccount;
    private final Timer timer;
    private volatile boolean closed;
    private Boolean sessionActive;

    public PremarketTradingPanel(String symbol, SignalWebSocketServer server) {
        this.symbol = symbol; this.server = server;
        quantity.putClientProperty("bmtrader.premarketNumberInput", true);
        limitPrice.putClientProperty("bmtrader.premarketNumberInput", true);
        offset.putClientProperty("bmtrader.premarketNumberInput", true);
        stopPrice.putClientProperty("bmtrader.premarketNumberInput", true);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createTitledBorder("Premarket / After-hours"));
        JPanel inputs = new JPanel(new GridLayout(2, 2, 4, 4));
        inputs.add(new JLabel("Shares")); inputs.add(quantity);
        inputs.add(new JLabel("Offset (%)")); inputs.add(offset); add(inputs);
        JPanel stopRow = new JPanel(new GridLayout(1, 2, 4, 4));
        stopRow.add(stopPrice); stopRow.add(setStop); add(stopRow); add(stopStatus);
        setStop.addActionListener(event -> {
            try {
                JsonObject action = new JsonObject(); action.addProperty("type", "premarket_stop"); action.addProperty("symbol", symbol);
                double price = Double.parseDouble(stopPrice.getText().trim());
                if (!com.bookmap.plugin.rong.miniviteapp.runtime.PremarketTrading.validFixedPrice(price)) throw new IllegalArgumentException("Enter a positive stop price at the supported increment");
                action.addProperty("price", price); server.dispatchTradingAction(action);
            } catch (IllegalArgumentException error) { status("Stop blocked: " + error.getMessage()); }
        });
        JPanel grid = new JPanel(new GridLayout(2, 2, 6, 6));
        grid.add(button("Buy Now", "buy_now", true)); grid.add(button("Buy Bid", "buy_bid", true));
        grid.add(button("Sell Now", "sell_now", false)); grid.add(button("Sell Ask", "sell_ask", false)); add(grid);
        JPanel priceRow = new JPanel(new GridLayout(1, 2, 4, 4));
        priceRow.add(new JLabel("Limit price")); priceRow.add(limitPrice); add(priceRow);
        JPanel limits = new JPanel(new GridLayout(1, 2, 6, 6));
        limits.add(button("Buy Limit", "buy_limit", true)); limits.add(button("Sell Limit", "sell_limit", false)); add(limits);
        quantity.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { publishShares(); updateSession(); }
            public void removeUpdate(DocumentEvent event) { publishShares(); updateSession(); }
            public void changedUpdate(DocumentEvent event) { publishShares(); updateSession(); }
        });
        limitPrice.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { updateSession(); }
            public void removeUpdate(DocumentEvent event) { updateSession(); }
            public void changedUpdate(DocumentEvent event) { updateSession(); }
        });
        publishShares();
        JButton cancelAll = button("Cancel All", "cancel_all", false);
        cancelAll.setBackground(new Color(75, 85, 100));
        JPanel cancel = new JPanel(new BorderLayout()); cancel.add(cancelAll, BorderLayout.CENTER); add(cancel);
        JButton flatten = button("Flatten", "flatten", false);
        JPanel exit = new JPanel(new BorderLayout()); exit.add(flatten, BorderLayout.CENTER); add(exit);
        add(account); add(status);
        status("Select local stop with hover + Z before entry. No broker stop is sent.");
        JLabel note = new JLabel(VerticalWindowLayout.wrapText("Hover + Z: set or move local stop. Triggers 1 cent beyond level; cancels orders, then exits 1 cent past bid/ask. Keep plugin and data running. Hover + L: limit entry. Fills are not guaranteed. Results: Action Log.", 200)); add(note);
        for (Component child : getComponents()) if (child instanceof JComponent) ((JComponent) child).setAlignmentX(Component.LEFT_ALIGNMENT);
        server.registerAccountStateListener(accountListener);
        server.registerPremarketStatus(symbol, text -> SwingUtilities.invokeLater(() -> { if (!closed) status(text); }));
        timer = new Timer(500, event -> updateSession()); timer.start(); updateSession();
    }
    private JButton button(String label, String operation, boolean buy) {
        JButton button = new JButton(label); button.putClientProperty("operation", operation); buttons.add(button);
        button.setBackground(buy ? new Color(38, 139, 88) : new Color(180, 62, 62)); button.setForeground(Color.WHITE);
        button.addActionListener(event -> {
            PluginLog.action(symbol, "Premarket button clicked: " + label);
            try {
                JsonObject action = operation.endsWith("_limit") ? fixedAction(symbol, operation, quantity.getText(), limitPrice.getText())
                        : action(symbol, operation, quantity.getText(), offset.getText());
                status(label + " requested. See Action Log for broker result.");
                server.dispatchTradingAction(action);
            } catch (IllegalArgumentException error) { status(error.getMessage()); PluginLog.action(symbol, "Premarket button blocked: " + error.getMessage()); }
        }); return button;
    }
    private void publishShares() { server.setPremarketShares(symbol, quantity.getText()); }
    public static JsonObject fixedAction(String symbol, String operation, String shares, String priceText) {
        JsonObject action = action(symbol, operation, shares, "1");
        double price;
        try { price = Double.parseDouble(priceText.trim()); }
        catch (RuntimeException error) { throw new IllegalArgumentException("Enter a valid limit price"); }
        if (!com.bookmap.plugin.rong.miniviteapp.runtime.PremarketTrading.validFixedPrice(price)) throw new IllegalArgumentException("Enter a positive price: cents above $1, up to four decimals below $1");
        action.addProperty("price", price); return action;
    }
    public static JsonObject action(String symbol, String operation, String shares, String percent) {
        if (operation.equals("cancel_all")) {
            JsonObject action = new JsonObject(); action.addProperty("type", "premarket_order"); action.addProperty("symbol", symbol); action.addProperty("operation", operation); return action;
        }
        double count = 0, padding;
        try { if (!operation.equals("flatten")) count = Double.parseDouble(shares.trim()); padding = Double.parseDouble(percent.trim()); }
        catch (RuntimeException error) { throw new IllegalArgumentException("Enter whole shares and a numeric offset percentage"); }
        if (!operation.equals("flatten") && (!Double.isFinite(count) || count <= 0 || count != Math.rint(count) || count > Integer.MAX_VALUE))
            throw new IllegalArgumentException("Enter a positive whole-share quantity");
        if (!Double.isFinite(padding) || padding <= 0 || padding >= 100) throw new IllegalArgumentException("Offset must be greater than 0 and less than 100%");
        JsonObject action = new JsonObject(); action.addProperty("type", "premarket_order"); action.addProperty("symbol", symbol);
        action.addProperty("operation", operation); action.addProperty("quantity", count); action.addProperty("offsetPercent", padding); return action;
    }
    private void updateSession() {
        boolean active = MarketClock.isExtendedTradingTime(System.currentTimeMillis());
        setStop.setEnabled(active);
        JsonObject stop = server.premarketStop(symbol);
        stopStatus.setText(VerticalWindowLayout.wrapText(stop.has("price")
                ? "Local stop: " + stop.get("price").getAsString() + " — " + stop.get("status").getAsString()
                : "Local stop: hover + Z, or enter price", 200));
        for (JButton button : buttons) {
            boolean valid = active;
            String operation = (String) button.getClientProperty("operation");
            if (operation.equals("cancel_all")) valid = !MarketClock.isRegularTradingTime(System.currentTimeMillis());
            if (operation.endsWith("_limit")) try { fixedAction(symbol, operation, quantity.getText(), limitPrice.getText()); } catch (IllegalArgumentException error) { valid = false; }
            button.setEnabled(valid);
        }
        if (!active && !Boolean.FALSE.equals(sessionActive)) status("Outside extended hours: weekdays 07:00–09:30 and 16:00–20:00 ET");
        sessionActive = active;
    }
    private void status(String text) { status.setText(VerticalWindowLayout.wrapText(text, 200)); }
    private void updateAccount(AccountStateDefinition state) {
        if (!symbol.equals(state.getSymbol())) return;
        SwingUtilities.invokeLater(() -> {
            if (closed) return;
            double net = state.getPosition() == null ? 0 : state.getPosition().getNetQuantity();
            account.setText(VerticalWindowLayout.wrapText("Position: " + (net == 0 ? "flat" : net > 0 ? "long " + net : "short " + Math.abs(net)) + ". Working orders: " + state.getOpenOrders().size(), 200));
        });
    }
    @Override public void close() { closed = true; timer.stop(); server.removePremarketShares(symbol); server.unregisterPremarketStatus(symbol); server.unregisterAccountStateListener(accountListener); }
}
