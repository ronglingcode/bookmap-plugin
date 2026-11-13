package com.bookmap.plugin.rong;

import com.google.gson.JsonObject;
import java.awt.BorderLayout;
import java.awt.Font;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

final class ReplayTradeWindow {
    private final String symbol;
    private final Consumer<JsonObject> publish;
    private final String sourceId = UUID.randomUUID().toString();
    private long epoch = 1;
    private long sequence;
    private long marketTime;
    private double price = Double.NaN;
    private long quantity;
    private double averagePrice;
    private String positionId;
    private volatile boolean disposed;
    private JFrame frame;
    private Timer refresh;

    ReplayTradeWindow(String symbol, Consumer<JsonObject> publish) {
        this.symbol = symbol;
        this.publish = publish;
    }

    void open() {
        SwingUtilities.invokeLater(this::show);
    }

    private void show() {
        if (disposed) return;
        frame = new JFrame("Trade: " + symbol + " [REPLAY]");
        frame.setAlwaysOnTop(true);
        frame.getRootPane().putClientProperty("bmtrader.noChartHover", true);
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JLabel banner = new JLabel("REPLAY · Simulated fills");
        banner.setFont(banner.getFont().deriveFont(Font.BOLD));
        panel.add(banner, BorderLayout.NORTH);
        JLabel status = new JLabel("Waiting for a recorded trade");
        panel.add(status, BorderLayout.CENTER);
        JPanel controls = new JPanel();
        JSpinner shares = new JSpinner(new SpinnerNumberModel(1, 1, Integer.MAX_VALUE, 1));
        JButton buy = new JButton("Buy (simulated)");
        JButton sell = new JButton("Sell (simulated)");
        sell.setToolTipText("Enter the shares to exit, up to the number held.");
        buy.setEnabled(false);
        sell.setEnabled(false);
        buy.addActionListener(event -> submit(shares, true));
        sell.addActionListener(event -> submit(shares, false));
        controls.add(new JLabel("Shares"));
        controls.add(shares);
        controls.add(buy);
        controls.add(sell);
        panel.add(controls, BorderLayout.SOUTH);
        refresh = new Timer(200, event -> {
            synchronized (this) {
                boolean ready = !disposed && Double.isFinite(price);
                buy.setEnabled(ready);
                sell.setEnabled(ready && quantity > 0);
                status.setText(ready ? String.format(Locale.ROOT, "$%.4f · %d shares held", price, quantity)
                        : "Waiting for a recorded trade");
            }
        });
        frame.setContentPane(panel);
        frame.pack();
        frame.setVisible(true);
        refresh.start();
    }

    private synchronized void submit(JSpinner shares, boolean buying) {
        try { shares.commitEdit(); } catch (java.text.ParseException invalid) { return; }
        int size = (Integer) shares.getValue();
        if (!buying && size > quantity) {
            java.awt.Toolkit.getDefaultToolkit().beep();
            shares.setValue((int) Math.max(1, Math.min(quantity, Integer.MAX_VALUE)));
            return;
        }
        if (buying) buy(size); else sell(size);
    }

    synchronized void observeTrade(double tradePrice, long timestamp) {
        if (disposed || timestamp <= 0 || timestamp < marketTime
                || !Double.isFinite(tradePrice) || tradePrice <= 0) return;
        marketTime = timestamp;
        price = tradePrice;
    }

    synchronized void observeTime(long timestamp) {
        if (disposed || timestamp <= 0) return;
        if (timestamp < marketTime) {
            epoch++;
            sequence = 0;
            price = Double.NaN;
            quantity = 0;
            averagePrice = 0;
            positionId = null;
            marketTime = timestamp;
            publish.accept(event("cairo_replay_reset"));
        }
        marketTime = timestamp;
    }

    synchronized void buy(int shares) {
        if (disposed || !Double.isFinite(price) || shares <= 0) return;
        boolean opening = quantity == 0;
        averagePrice = (averagePrice * quantity + price * shares) / (quantity + shares);
        quantity += shares;
        publishFill(shares, true, opening);
    }

    synchronized void sell(int shares) {
        if (disposed || !Double.isFinite(price) || shares <= 0 || shares > quantity) return;
        quantity -= shares;
        publishFill(shares, false, false);
        if (quantity == 0) {
            averagePrice = 0;
            positionId = null;
        }
    }

    private void publishFill(int shares, boolean buying, boolean opening) {
        JsonObject fill = event("cairo_replay_fill");
        String executionId = sourceId + ":" + epoch + ":" + sequence;
        if (opening) positionId = executionId;
        fill.addProperty("executionId", executionId);
        fill.addProperty("positionId", positionId);
        fill.addProperty("side", buying ? "BUY" : "SELL");
        fill.addProperty("quantity", shares);
        fill.addProperty("price", price);
        fill.addProperty("positionQuantity", quantity);
        fill.addProperty("positionAveragePrice", averagePrice);
        fill.addProperty("opening", opening);
        publish.accept(fill);
    }

    private JsonObject event(String type) {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        event.addProperty("version", 1);
        event.addProperty("mode", "replay");
        event.addProperty("sourceInstanceId", sourceId);
        event.addProperty("symbol", symbol);
        event.addProperty("epoch", epoch);
        event.addProperty("sequence", ++sequence);
        event.addProperty("eventTime", Long.toString(marketTime));
        return event;
    }

    synchronized void dispose() {
        disposed = true;
        SwingUtilities.invokeLater(() -> {
            if (refresh != null) refresh.stop();
            if (frame != null) frame.dispose();
        });
    }
}
