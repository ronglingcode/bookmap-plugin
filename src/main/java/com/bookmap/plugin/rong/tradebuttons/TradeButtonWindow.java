package com.bookmap.plugin.rong.tradebuttons;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
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
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.JTextField;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;

import com.bookmap.plugin.rong.BookmapPriceNormalizer;
import com.bookmap.plugin.rong.CorePlanConfigDefinition;
import com.bookmap.plugin.rong.NewPositionDefinition;
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
    private static final int WALL_THRESHOLD_REFRESH_MS = 1_000;
    private static final double PRIMARY_ENTRY_BUTTON_WEIGHT = 1.35;
    private static final double SECONDARY_ENTRY_BUTTON_WEIGHT = 1.0;
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
    private final SignalWebSocketServer.CorePlanConfigListener corePlanConfigListener;
    private final SignalWebSocketServer.NewPositionListener newPositionListener;
    private JFrame frame;
    private JPanel buttonPanel;
    private JLabel shiftModeLabel;
    private JLabel wallThresholdLabel;
    private Timer wallThresholdTimer;
    private volatile CorePlanConfigDefinition corePlanConfig;
    private JDialog corePlanDialog;
    private JTextField coreTargetField;
    private JTextField coreCountField;
    private JLabel corePlanStatusLabel;
    private JButton corePlanUpdateButton;
    private String pendingCorePlanRequestId = "";
    private String lastReminderTradeId = "";
    private JDialog newPositionReminderDialog;
    private String lastNewPositionReminderEventId = "";
    private volatile boolean disposed;

    public TradeButtonWindow(String symbol, SignalWebSocketServer server,
                             IntSupplier wallThresholdFloorSupplier) {
        this.symbol = symbol;
        this.server = server;
        this.wallThresholdFloorSupplier = wallThresholdFloorSupplier == null
                ? () -> WallThresholdConfig.DEFAULT_THRESHOLD_FLOOR
                : wallThresholdFloorSupplier;
        this.buttonConfigListener = this::setButtons;
        this.corePlanConfigListener = this::setCorePlanConfig;
        this.newPositionListener = this::onNewPosition;
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
        server.registerCorePlanConfigListener(symbol, corePlanConfigListener);
        server.registerNewPositionListener(symbol, newPositionListener);
    }

    private void setButtons(List<TradebookButtonGroup> tradebooks) {
        SwingUtilities.invokeLater(() -> {
            if (!disposed) {
                renderButtons(tradebooks);
            }
        });
    }

    private void onNewPosition(NewPositionDefinition position) {
        SwingUtilities.invokeLater(() -> {
            if (disposed || position.getEventId().equals(lastNewPositionReminderEventId)) {
                return;
            }
            lastNewPositionReminderEventId = position.getEventId();
            showNewPositionReminder(position);
        });
    }

    private void showNewPositionReminder(NewPositionDefinition position) {
        closeNewPositionReminder();
        newPositionReminderDialog = new JDialog(
                frame,
                "New Position Reminder - " + symbol,
                false);
        newPositionReminderDialog.setAlwaysOnTop(true);
        newPositionReminderDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        newPositionReminderDialog.setResizable(false);
        JDialog openedDialog = newPositionReminderDialog;
        newPositionReminderDialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                if (newPositionReminderDialog == openedDialog) {
                    newPositionReminderDialog = null;
                }
            }
        });

        String side = position.isLongPosition() ? "LONG" : "SHORT";
        String averagePrice = position.getAveragePrice() > 0
                ? formatPrice(position.getAveragePrice())
                : "waiting";
        JLabel positionSummary = new JLabel(
                "<html><div style='width:390px'>"
                        + "<b>New " + side + " position detected for " + symbol + ".</b>"
                        + "<br>Quantity: " + formatQuantity(position.getNetQuantity())
                        + " &nbsp; Average: " + averagePrice
                        + "</div></html>");

        JPanel reminderItems = buildNewPositionReminderItems();

        JButton doneButton = new JButton("Done");
        doneButton.addActionListener(e -> closeNewPositionReminder());
        JPanel actions = new JPanel(new GridLayout(1, 1));
        actions.add(doneButton);

        JPanel root = new JPanel(new BorderLayout(8, 12));
        root.setBorder(new EmptyBorder(14, 14, 14, 14));
        root.add(positionSummary, BorderLayout.NORTH);
        root.add(reminderItems, BorderLayout.CENTER);
        root.add(actions, BorderLayout.SOUTH);
        newPositionReminderDialog.setContentPane(root);
        newPositionReminderDialog.pack();
        newPositionReminderDialog.setLocationRelativeTo(frame);
        newPositionReminderDialog.setVisible(true);
        newPositionReminderDialog.toFront();
        PluginLog.action(symbol, "New position reminder: review invalidation condition");
    }

    private JPanel buildNewPositionReminderItems() {
        JPanel reminderItems = new JPanel();
        reminderItems.setLayout(new BoxLayout(reminderItems, BoxLayout.Y_AXIS));
        reminderItems.setBorder(BorderFactory.createTitledBorder("Reminders"));
        reminderItems.add(new JLabel(
                "<html><div style='width:370px'>"
                        + "<b>Review the invalidation condition for this trade.</b>"
                        + "<br>Be specific about what price action or market behavior invalidates the setup."
                        + "</div></html>"));
        return reminderItems;
    }

    private void closeNewPositionReminder() {
        if (newPositionReminderDialog != null) {
            JDialog dialog = newPositionReminderDialog;
            newPositionReminderDialog = null;
            dialog.dispose();
        }
    }

    private void setCorePlanConfig(CorePlanConfigDefinition config) {
        SwingUtilities.invokeLater(() -> {
            if (disposed) {
                return;
            }
            corePlanConfig = config;
            if (!pendingCorePlanRequestId.isEmpty()
                    && pendingCorePlanRequestId.equals(config.getRequestId())) {
                if ("success".equalsIgnoreCase(config.getUpdateStatus())) {
                    PluginLog.action(symbol, "Exit plan updated: target "
                            + formatPrice(config.getCoreTarget()) + ", count " + config.getCoreCount());
                    closeCorePlanDialog();
                } else if ("error".equalsIgnoreCase(config.getUpdateStatus())) {
                    pendingCorePlanRequestId = "";
                    if (corePlanUpdateButton != null) {
                        corePlanUpdateButton.setEnabled(true);
                    }
                    if (corePlanStatusLabel != null) {
                        corePlanStatusLabel.setForeground(SHORT_TRADEBOOK_BUTTON_COLOR);
                        corePlanStatusLabel.setText(config.getError().isEmpty()
                                ? "ViteApp rejected the update."
                                : config.getError());
                    }
                }
            }

            if (config.hasActiveTrade() && config.isReminderRequested()) {
                String reminderId = config.getTradeId().isEmpty()
                        ? symbol + ":" + config.getTimestamp()
                        : config.getTradeId();
                if (!reminderId.equals(lastReminderTradeId)) {
                    lastReminderTradeId = reminderId;
                    showCorePlanDialog(true);
                }
            }
        });
    }

    private void showCorePlanDialog(boolean reminder) {
        if (disposed) {
            return;
        }
        CorePlanConfigDefinition config = corePlanConfig;
        if (config == null || !config.hasActiveTrade()) {
            JOptionPane.showMessageDialog(
                    frame,
                    "No active trade plan is available for " + symbol + ".",
                    "Update Exit Plan",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (corePlanDialog != null && corePlanDialog.isDisplayable()) {
            corePlanDialog.toFront();
            corePlanDialog.requestFocus();
            return;
        }

        corePlanDialog = new JDialog(
                frame,
                (reminder ? "Review" : "Update") + " Exit Plan - " + symbol,
                false);
        corePlanDialog.setAlwaysOnTop(true);
        corePlanDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        corePlanDialog.setResizable(false);
        corePlanDialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                clearCorePlanDialogReferences();
            }
        });

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        JLabel info = new JLabel(buildCorePlanInfo(config));
        root.add(info, BorderLayout.NORTH);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JPanel fields = new JPanel(new GridLayout(2, 2, 8, 8));
        fields.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Editable"),
                BorderFactory.createEmptyBorder(4, 6, 6, 6)));
        fields.add(new JLabel("Core target"));
        coreTargetField = new JTextField(formatPrice(config.getCoreTarget()), 10);
        fields.add(coreTargetField);
        fields.add(new JLabel("Core count"));
        coreCountField = new JTextField(Integer.toString(config.getCoreCount()), 10);
        fields.add(coreCountField);
        content.add(fields);
        content.add(createPlanReminderPanel(config));
        root.add(content, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(8, 8));
        corePlanStatusLabel = new JLabel(reminder
                ? "Third partial taken — review or keep this plan."
                : "The final restricted partials must exit at the buffer or better.");
        footer.add(corePlanStatusLabel, BorderLayout.NORTH);
        JPanel actions = new JPanel(new GridLayout(1, 2, 8, 0));
        JButton keepButton = new JButton("Keep Current");
        keepButton.addActionListener(e -> closeCorePlanDialog());
        corePlanUpdateButton = new JButton("Update");
        corePlanUpdateButton.addActionListener(e -> submitCorePlanUpdate());
        actions.add(keepButton);
        actions.add(corePlanUpdateButton);
        footer.add(actions, BorderLayout.SOUTH);
        root.add(footer, BorderLayout.SOUTH);

        corePlanDialog.setContentPane(root);
        corePlanDialog.pack();
        corePlanDialog.setLocationRelativeTo(frame);
        corePlanDialog.setVisible(true);
        coreTargetField.requestFocusInWindow();
        coreTargetField.selectAll();
    }

    private String buildCorePlanInfo(CorePlanConfigDefinition config) {
        return "<html><b>" + (config.isLongPosition() ? "Long" : "Short") + " " + symbol + "</b>"
                + " &nbsp; Entry: " + formatPrice(config.getEntryPrice())
                + " &nbsp; 90% buffer: " + formatPrice(config.getBufferedTarget())
                + "<br>Partials taken: " + config.getPartialsTaken() + "/10"
                + " &nbsp; (first 3 are always unrestricted)</html>";
    }

    private JPanel createPlanReminderPanel(CorePlanConfigDefinition config) {
        JPanel reminder = new JPanel();
        reminder.setLayout(new BoxLayout(reminder, BoxLayout.Y_AXIS));
        reminder.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Plan reminder"),
                BorderFactory.createEmptyBorder(4, 6, 6, 6)));

        JLabel runnerCount = new JLabel("Runner count: " + config.getRunnerCount());
        runnerCount.setAlignmentX(Component.LEFT_ALIGNMENT);
        reminder.add(runnerCount);

        JLabel runnerConditionLabel = new JLabel("Runner condition");
        runnerConditionLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        reminder.add(runnerConditionLabel);
        reminder.add(createReadOnlyPlanText(config.getRunnerCondition(), 2));

        JLabel corePlanLabel = new JLabel("Core plan");
        corePlanLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        reminder.add(corePlanLabel);
        reminder.add(createReadOnlyPlanText(config.getCorePlan(), 5));
        return reminder;
    }

    private JTextArea createReadOnlyPlanText(String value, int preferredRows) {
        String displayValue = value == null || value.trim().isEmpty() ? "—" : value.trim();
        int lineCount = displayValue.split("\\R", -1).length;
        JTextArea text = new JTextArea(
                displayValue,
                Math.max(1, Math.min(6, Math.max(preferredRows, lineCount))),
                42);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setBackground(THRESHOLD_BACKGROUND);
        text.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        text.setAlignmentX(Component.LEFT_ALIGNMENT);
        return text;
    }

    private void submitCorePlanUpdate() {
        CorePlanConfigDefinition config = corePlanConfig;
        if (config == null || !config.hasActiveTrade()
                || coreTargetField == null || coreCountField == null) {
            return;
        }

        double target;
        int count;
        try {
            target = Double.parseDouble(coreTargetField.getText().trim());
            count = Integer.parseInt(coreCountField.getText().trim());
        } catch (NumberFormatException e) {
            showCorePlanValidationError("Target must be a number and count must be a whole number.");
            return;
        }
        if (!Double.isFinite(target) || target <= 0) {
            showCorePlanValidationError("Core target must be a positive number.");
            return;
        }
        if (count < 0 || count > 7) {
            showCorePlanValidationError("Core count must be an integer from 0 to 7.");
            return;
        }
        if ((config.isLongPosition() && target <= config.getEntryPrice())
                || (!config.isLongPosition() && target >= config.getEntryPrice())) {
            showCorePlanValidationError("Core target must be "
                    + (config.isLongPosition() ? "above" : "below")
                    + " entry " + formatPrice(config.getEntryPrice()) + ".");
            return;
        }

        pendingCorePlanRequestId = symbol + ":" + System.nanoTime();
        corePlanUpdateButton.setEnabled(false);
        corePlanStatusLabel.setForeground(THRESHOLD_TEXT_COLOR);
        corePlanStatusLabel.setText("Saving in ViteApp...");

        JsonObject json = new JsonObject();
        json.addProperty("type", "core_plan_update");
        BookmapPriceNormalizer.addWirePriceUnit(json);
        json.addProperty("symbol", symbol);
        json.addProperty("coreTarget", target);
        json.addProperty("coreCount", count);
        json.addProperty("requestId", pendingCorePlanRequestId);
        json.addProperty("timestamp", System.currentTimeMillis());
        server.broadcast(json.toString());
        PluginLog.action(symbol, "Requested exit plan update: target "
                + formatPrice(target) + ", count " + count);
    }

    private void showCorePlanValidationError(String message) {
        if (corePlanStatusLabel != null) {
            corePlanStatusLabel.setForeground(SHORT_TRADEBOOK_BUTTON_COLOR);
            corePlanStatusLabel.setText(message);
        }
    }

    private void closeCorePlanDialog() {
        if (corePlanDialog != null) {
            corePlanDialog.dispose();
        }
        clearCorePlanDialogReferences();
    }

    private void clearCorePlanDialogReferences() {
        corePlanDialog = null;
        coreTargetField = null;
        coreCountField = null;
        corePlanStatusLabel = null;
        corePlanUpdateButton = null;
        pendingCorePlanRequestId = "";
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
        hotkeyPanel.add(createHotkeyButton("Swap", "swap", "KeyW"));
        hotkeyPanel.add(createCorePlanButton());
        return hotkeyPanel;
    }

    private JButton createCorePlanButton() {
        JButton button = new JButton("Update Plan");
        applyHotkeyButtonStyle(button);
        button.addActionListener(e -> showCorePlanDialog(false));
        return button;
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

        JPanel entryMethodPanel = createEntryMethodPanel(tradebook);
        tradebookPanel.add(entryMethodPanel, BorderLayout.CENTER);
        return tradebookPanel;
    }

    private JPanel createEntryMethodPanel(TradebookButtonGroup tradebook) {
        JPanel entryMethodPanel = new JPanel(new GridBagLayout());
        List<String> entryMethods = tradebook.getEntryMethods();
        for (int index = 0; index < entryMethods.size(); index++) {
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx = index;
            gbc.gridy = 0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = index == 0 ? PRIMARY_ENTRY_BUTTON_WEIGHT : SECONDARY_ENTRY_BUTTON_WEIGHT;
            gbc.insets = new Insets(0, 0, 0, index == entryMethods.size() - 1 ? 0 : 6);
            entryMethodPanel.add(createEntryButton(tradebook, entryMethods.get(index)), gbc);
        }
        return entryMethodPanel;
    }

    private JButton createEntryButton(TradebookButtonGroup tradebook, String entryMethod) {
        JButton button = new JButton(entryMethod);
        applyTradebookButtonStyle(button, tradebook.isLong());
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

    private void applyTradebookButtonStyle(JButton button, boolean sideIsLong) {
        Color color = getTradebookButtonColor(sideIsLong);
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

    private Color getTradebookButtonColor(boolean sideIsLong) {
        return sideIsLong ? LONG_TRADEBOOK_BUTTON_COLOR : SHORT_TRADEBOOK_BUTTON_COLOR;
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
        int thresholdFloor = getWallThresholdFloor();
        SignalWebSocketServer.OrderbookWallThreshold threshold =
                server.getOrderbookWallThreshold(symbol, thresholdFloor);
        if (!threshold.isAvailable()) {
            wallThresholdLabel.setText("Wall: waiting | " + getRegularSessionHighLowText());
            return;
        }

        String percentileSize = threshold.getPercentileMinSize() > 0
                ? formatShareSize(threshold.getPercentileMinSize())
                : "n/a";
        wallThresholdLabel.setText("Wall: " + formatShareSize(threshold.getEffectiveMinSize())
                + " (P" + formatPercentile(threshold.getPercentile())
                + "=" + percentileSize
                + ", Top3=" + formatLargestSizes(threshold.getLargestLevelSizes())
                + ", floor=" + formatShareSize(threshold.getAbsoluteMinSize()) + ")"
                + " | " + getRegularSessionHighLowText());
    }

    private String getRegularSessionHighLowText() {
        return server.describeRegularSessionHighLow(symbol);
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
        BookmapPriceNormalizer.addWirePriceUnit(json);
        json.addProperty("symbol", symbol);
        json.addProperty("button_id", tradebook.getId() + ":" + entryMethod + ":" + orderType);
        json.addProperty("button_name", (useMarketOrder ? "Mkt: " : "Breakout: ") + tradebook.getLabel() + ": " + entryMethod);
        json.addProperty("use_market_order", useMarketOrder);
        json.addProperty("order_type", orderType);
        json.addProperty("sideIsLong", tradebook.isLong());
        json.addProperty("tradebook_id", tradebook.getTradebookId());
        json.addProperty("tradebook_name", tradebook.getTradebookName());
        json.addProperty("entry_method", entryMethod);
        json.addProperty("timestamp", System.currentTimeMillis());
        if (useMarketOrder) {
            Double estimatedEntryPrice = server.getMarketEntryEstimate(symbol, tradebook.isLong());
            if (estimatedEntryPrice != null) {
                json.addProperty("estimated_entry_price", estimatedEntryPrice);
            }
        }
        server.appendRegularSessionHighLow(symbol, json);
        server.broadcast(json.toString());
        PluginLog.action(symbol, "Button send " + orderType + " " + tradebook.getLabel() + " " + entryMethod);
    }

    private void sendHotkeyButtonMessage(String buttonId, String buttonName, String keyCode, boolean shiftKey) {
        HotkeyButtonAction.send(server, symbol, buttonId, buttonName, keyCode, shiftKey);
    }

    private int getWallThresholdFloor() {
        try {
            return Math.max(0, wallThresholdFloorSupplier.getAsInt());
        } catch (RuntimeException e) {
            return WallThresholdConfig.DEFAULT_THRESHOLD_FLOOR;
        }
    }

    private String formatPrice(double price) {
        if (!Double.isFinite(price) || price <= 0) {
            return "";
        }
        return String.format("%.2f", price);
    }

    private String formatQuantity(double quantity) {
        if (Math.abs(quantity - Math.rint(quantity)) < 0.00001) {
            return String.format(Locale.US, "%.0f", quantity);
        }
        return String.format(Locale.US, "%.2f", quantity);
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

    static String formatLargestSizes(List<Integer> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return "n/a";
        }
        StringBuilder text = new StringBuilder();
        for (Integer size : sizes) {
            if (text.length() > 0) {
                text.append('/');
            }
            text.append(formatShareSize(size == null ? 0 : size));
        }
        return text.toString();
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
        server.unregisterCorePlanConfigListener(symbol, corePlanConfigListener);
        server.unregisterNewPositionListener(symbol, newPositionListener);
        SwingUtilities.invokeLater(() -> {
            closeCorePlanDialog();
            closeNewPositionReminder();
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
