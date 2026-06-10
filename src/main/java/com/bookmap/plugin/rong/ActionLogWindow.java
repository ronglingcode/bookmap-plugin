package com.bookmap.plugin.rong;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

/**
 * Small action-only log window. It intentionally stays separate from PluginLog's
 * verbose file logging so the UI only shows trading actions worth glancing at.
 */
public class ActionLogWindow {

    private static final int MAX_LINES = 20;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Deque<String> lines = new ArrayDeque<>();
    private static final Map<String, AccountStateDefinition> accountStates = new LinkedHashMap<>();
    private static final String[] POSITION_COLUMNS = {"Symbol", "Side", "Risk %", "Avg"};
    private static final String[] ORDER_COLUMNS = {"Symbol", "Role", "Side", "Qty", "Type", "Price", "Ref"};
    private static JFrame frame;
    private static JLabel accountStatusLabel;
    private static JLabel positionStatusLabel;
    private static JLabel orderStatusLabel;
    private static DefaultTableModel positionTableModel;
    private static DefaultTableModel orderTableModel;
    private static JTextArea textArea;

    private ActionLogWindow() {}

    public static void showWindow() {
        SwingUtilities.invokeLater(ActionLogWindow::ensureWindow);
    }

    public static void append(String symbol, String source, String message) {
        SwingUtilities.invokeLater(() -> {
            ensureWindow();
            while (lines.size() >= MAX_LINES) {
                lines.removeFirst();
            }
            lines.addLast(formatLine(symbol, source, message));
            textArea.setText(String.join("\n", lines));
            textArea.setCaretPosition(textArea.getDocument().getLength());
        });
    }

    public static void updateAccountState(AccountStateDefinition state) {
        if (state == null || state.getSymbol().isEmpty()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            ensureWindow();
            accountStates.put(state.getSymbol(), state);
            renderAccountState();
        });
    }

    public static void dispose() {
        SwingUtilities.invokeLater(() -> {
            lines.clear();
            accountStates.clear();
            if (frame != null) {
                frame.dispose();
                frame = null;
                accountStatusLabel = null;
                positionStatusLabel = null;
                orderStatusLabel = null;
                positionTableModel = null;
                orderTableModel = null;
                textArea = null;
            }
        });
    }

    private static void ensureWindow() {
        if (frame != null) {
            return;
        }
        frame = new JFrame("Rong Logs");
        frame.setAlwaysOnTop(true);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        textArea = new JTextArea(20, 54);
        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        accountStatusLabel = new JLabel("Account: waiting for ViteApp");
        positionStatusLabel = new JLabel("Positions (0)");
        orderStatusLabel = new JLabel("Open Orders (0)");
        positionTableModel = createTableModel(POSITION_COLUMNS);
        orderTableModel = createTableModel(ORDER_COLUMNS);
        JTable positionTable = createTable(positionTableModel);
        JTable orderTable = createTable(orderTableModel);

        JPanel accountPanel = new JPanel(new BorderLayout(0, 6));
        accountPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        accountPanel.add(accountStatusLabel, BorderLayout.NORTH);

        JPanel tablesPanel = new JPanel(new GridLayout(2, 1, 0, 6));
        tablesPanel.add(createSection("Positions", positionStatusLabel, positionTable, 82));
        tablesPanel.add(createSection("Open Orders", orderStatusLabel, orderTable, 134));
        accountPanel.add(tablesPanel, BorderLayout.CENTER);

        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createTitledBorder("Actions"));
        logPanel.add(new JScrollPane(textArea), BorderLayout.CENTER);

        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(accountPanel, BorderLayout.NORTH);
        frame.getContentPane().add(logPanel, BorderLayout.CENTER);
        frame.setPreferredSize(new Dimension(760, 620));
        frame.pack();
        frame.setVisible(true);
        renderAccountState();
    }

    private static String formatLine(String symbol, String source, String message) {
        String cleanSymbol = symbol == null ? "" : symbol.trim();
        String cleanSource = source == null ? "" : source.trim();
        String prefix = LocalTime.now().format(TIME_FMT);
        if (!cleanSymbol.isEmpty()) {
            prefix += " " + cleanSymbol;
        }
        if (!cleanSource.isEmpty()) {
            prefix += " [" + cleanSource + "]";
        }
        return prefix + " " + message;
    }

    private static DefaultTableModel createTableModel(String[] columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private static JTable createTable(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        table.setRowHeight(20);
        table.setFillsViewportHeight(true);
        return table;
    }

    private static JPanel createSection(String title, JLabel statusLabel, JTable table, int height) {
        JPanel section = new JPanel(new BorderLayout(0, 2));
        statusLabel.setText(title + " (0)");
        section.add(statusLabel, BorderLayout.NORTH);
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(720, height));
        section.add(scrollPane, BorderLayout.CENTER);
        return section;
    }

    private static void renderAccountState() {
        if (positionTableModel == null || orderTableModel == null) {
            return;
        }

        positionTableModel.setRowCount(0);
        orderTableModel.setRowCount(0);

        List<AccountStateDefinition> states = new ArrayList<>(accountStates.values());
        states.sort(Comparator.comparing(AccountStateDefinition::getSymbol));

        if (states.isEmpty()) {
            accountStatusLabel.setText("Account: waiting for ViteApp");
            positionStatusLabel.setText("Positions (0)");
            orderStatusLabel.setText("Open Orders (0)");
            return;
        }

        int positionCount = 0;
        int orderCount = 0;
        Set<String> orderPairKeys = new HashSet<>();
        for (AccountStateDefinition state : states) {
            if (state.hasOpenPosition()) {
                AccountPositionDefinition position = state.getPosition();
                positionTableModel.addRow(new Object[] {
                        state.getSymbol(),
                        position.getNetQuantity() > 0 ? "LONG" : "SHORT",
                        formatRiskPercent(position.getRiskPercent()),
                        formatPrice(position.getAveragePrice())
                });
                positionCount++;
            }

            for (AccountOrderDefinition order : state.getOpenOrders()) {
                String orderPairKey = getOrderPairKey(state.getSymbol(), order);
                if (!orderPairKey.isEmpty()) {
                    orderPairKeys.add(orderPairKey);
                }
                orderTableModel.addRow(new Object[] {
                        state.getSymbol(),
                        formatRole(order),
                        order.isBuy() ? "BUY" : "SELL",
                        formatQuantity(order.getQuantity()),
                        order.getOrderType(),
                        formatOrderPrice(order),
                        formatOrderRef(order)
                });
                orderCount++;
            }
        }

        int orderPairCount = orderPairKeys.size();
        accountStatusLabel.setText("Account: " + activeSummary(positionCount, orderCount, orderPairCount)
                + " | updated " + LocalTime.now().format(TIME_FMT));
        positionStatusLabel.setText("Positions (" + positionCount + ")");
        orderStatusLabel.setText("Open Orders (" + orderCount + ", " + orderPairCount + " pair(s))");
    }

    private static String activeSummary(int positionCount, int orderCount, int orderPairCount) {
        if (positionCount == 0 && orderCount == 0) {
            return "no open positions/orders";
        }
        return positionCount + " position(s), " + orderCount + " order(s), " + orderPairCount + " pair(s)";
    }

    private static String getOrderPairKey(String symbol, AccountOrderDefinition order) {
        if (!isExitOrderLeg(order)) {
            return "";
        }
        if (order.getPairIndex() > 0) {
            return symbol + "#" + order.getPairIndex();
        }
        if (!order.getParentOrderId().isEmpty()) {
            return symbol + "#" + order.getParentOrderId();
        }
        return "";
    }

    private static boolean isExitOrderLeg(AccountOrderDefinition order) {
        String role = order.getRole();
        return "STOP".equalsIgnoreCase(role) || "LIMIT".equalsIgnoreCase(role);
    }

    private static String formatRole(AccountOrderDefinition order) {
        String role = order.getRole();
        if (role == null || role.isEmpty()) {
            role = "ORDER";
        }
        if (order.getPairIndex() > 0 && ("STOP".equalsIgnoreCase(role) || "LIMIT".equalsIgnoreCase(role))) {
            return order.getPairIndex() + ":" + role;
        }
        return role;
    }

    private static String formatOrderPrice(AccountOrderDefinition order) {
        if ("MARKET".equalsIgnoreCase(order.getOrderType())
                || order.getPrice() <= 0
                || !Double.isFinite(order.getPrice())) {
            return "MKT";
        }
        return formatPrice(order.getPrice());
    }

    private static String formatOrderRef(AccountOrderDefinition order) {
        String orderId = order.getOrderId();
        if (orderId == null || orderId.isEmpty()) {
            return order.getSource();
        }
        if (orderId.length() <= 8) {
            return orderId;
        }
        return orderId.substring(orderId.length() - 8);
    }

    private static String formatQuantity(double quantity) {
        if (!Double.isFinite(quantity)) {
            return "";
        }
        if (quantity == Math.rint(quantity)) {
            return Long.toString(Math.round(quantity));
        }
        return String.format("%.2f", quantity);
    }

    private static String formatRiskPercent(double riskPercent) {
        if (!Double.isFinite(riskPercent)) {
            return "";
        }
        if (riskPercent == Math.rint(riskPercent)) {
            return Long.toString(Math.round(riskPercent)) + "%";
        }
        return String.format("%.1f%%", riskPercent);
    }

    private static String formatPrice(double price) {
        if (!Double.isFinite(price) || price <= 0) {
            return "";
        }
        return String.format("%.2f", price);
    }
}
