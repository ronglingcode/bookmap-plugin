package com.bookmap.plugin.rong;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Dimension;

import javax.swing.JCheckBox;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import javax.swing.SpinnerNumberModel;

import velox.gui.StrategyPanel;

/**
 * Settings panel for enabling/disabling automatic indicators.
 */
public class IndicatorSettingsPanel extends StrategyPanel {

    public IndicatorSettingsPanel(IndicatorConfig config, WallThresholdConfig wallThresholdConfig) {
        this(config, wallThresholdConfig, new NativeConnectionStatus());
    }

    public IndicatorSettingsPanel(IndicatorConfig config, WallThresholdConfig wallThresholdConfig,
            NativeConnectionStatus connectionStatus) {
        super("Indicators");
        setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 8, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        JLabel versionLabel = new JLabel(PluginVersion.NAME + " Version: " + PluginVersion.VERSION);
        add(versionLabel, gbc);

        gbc.gridy++;
        JPanel connectionPanel = new JPanel(new java.awt.GridLayout(0, 1, 0, 3));
        JLabel schwabStatus = connectionLabel();
        JLabel massiveHistoryStatus = connectionLabel();
        JLabel massiveStreamStatus = connectionLabel();
        connectionPanel.add(schwabStatus);
        connectionPanel.add(massiveHistoryStatus);
        connectionPanel.add(massiveStreamStatus);
        connectionStatus.addListener(status -> SwingUtilities.invokeLater(() -> {
            updateConnectionLabel(schwabStatus, "Schwab", status.getSchwab(), status.isSchwabConnected());
            updateConnectionLabel(massiveHistoryStatus, "Massive price history", status.getMassiveHistory(), status.isMassiveHistoryReady());
            updateConnectionLabel(massiveStreamStatus, "Massive stream", status.getMassiveStream(), status.isMassiveStreamReceiving());
        }));
        add(connectionPanel, gbc);

        gbc.gridy++;
        JCheckBox camPivotsCheckbox = new JCheckBox(
                "Camarilla Pivots",
                config.isEnabled(IndicatorConfig.CAM_PIVOTS));
        camPivotsCheckbox.addActionListener(e ->
                config.setEnabled(IndicatorConfig.CAM_PIVOTS, camPivotsCheckbox.isSelected()));
        add(camPivotsCheckbox, gbc);

        gbc.gridy++;
        JCheckBox vwapCheckbox = new JCheckBox(
                "VWAP (closed-minute values from ViteApp)",
                config.isEnabled(IndicatorConfig.VWAP));
        vwapCheckbox.addActionListener(e ->
                config.setEnabled(IndicatorConfig.VWAP, vwapCheckbox.isSelected()));
        add(vwapCheckbox, gbc);

        gbc.gridy++;
        JCheckBox wallLabelsCheckbox = new JCheckBox(
                "Order Wall Size Labels",
                config.isEnabled(IndicatorConfig.ORDER_WALL_SIZE_LABELS));
        wallLabelsCheckbox.addActionListener(e ->
                config.setEnabled(IndicatorConfig.ORDER_WALL_SIZE_LABELS, wallLabelsCheckbox.isSelected()));
        add(wallLabelsCheckbox, gbc);

        gbc.gridy++;
        JCheckBox wallChangeAlertsCheckbox = new JCheckBox(
                "Enable Order Change Alerts",
                config.areOrderChangeAlertsEnabled());
        wallChangeAlertsCheckbox.setToolTipText(
                "Global switch for order-change labels and sounds on all instruments");
        JCheckBox wallChangeSoundCheckbox = new JCheckBox(
                "Play Order Change Alert Sound",
                config.isEnabled(IndicatorConfig.ORDER_WALL_CHANGE_SOUND));
        wallChangeSoundCheckbox.setEnabled(wallChangeAlertsCheckbox.isSelected());
        wallChangeAlertsCheckbox.addActionListener(e -> {
            boolean enabled = wallChangeAlertsCheckbox.isSelected();
            config.setEnabled(IndicatorConfig.ORDER_WALL_CHANGE_ALERTS, enabled);
            wallChangeSoundCheckbox.setEnabled(enabled);
        });
        add(wallChangeAlertsCheckbox, gbc);

        gbc.gridy++;
        wallChangeSoundCheckbox.addActionListener(e ->
                config.setEnabled(IndicatorConfig.ORDER_WALL_CHANGE_SOUND, wallChangeSoundCheckbox.isSelected()));
        add(wallChangeSoundCheckbox, gbc);

        gbc.gridy++;
        JCheckBox wallBreakoutSignalsCheckbox = new JCheckBox(
                "Order Wall Breakout / Breakdown Signals",
                config.isEnabled(IndicatorConfig.ORDER_WALL_BREAKOUT_SIGNALS));
        wallBreakoutSignalsCheckbox.addActionListener(e ->
                config.setEnabled(
                        IndicatorConfig.ORDER_WALL_BREAKOUT_SIGNALS,
                        wallBreakoutSignalsCheckbox.isSelected()));
        add(wallBreakoutSignalsCheckbox, gbc);

        gbc.gridy++;
        JCheckBox patternSignalsCheckbox = new JCheckBox(
                "Bookmap Pattern Automation (display-only)",
                config.isEnabled(IndicatorConfig.BOOKMAP_PATTERN_SIGNALS));
        patternSignalsCheckbox.addActionListener(e ->
                config.setEnabled(IndicatorConfig.BOOKMAP_PATTERN_SIGNALS, patternSignalsCheckbox.isSelected()));
        add(patternSignalsCheckbox, gbc);

        gbc.gridy++;
        JPanel wallThresholdPanel = new JPanel(new GridBagLayout());
        GridBagConstraints thresholdGbc = new GridBagConstraints();
        thresholdGbc.insets = new Insets(0, 0, 0, 8);
        thresholdGbc.anchor = GridBagConstraints.WEST;
        thresholdGbc.gridx = 0;
        thresholdGbc.gridy = 0;
        wallThresholdPanel.add(new JLabel("Wall threshold floor"), thresholdGbc);

        thresholdGbc.gridx++;
        SpinnerNumberModel thresholdModel = new SpinnerNumberModel(
                wallThresholdConfig.getThresholdFloor(),
                0,
                WallThresholdConfig.MAX_THRESHOLD_FLOOR,
                500);
        JSpinner thresholdSpinner = new JSpinner(thresholdModel);
        thresholdSpinner.setPreferredSize(new Dimension(96, thresholdSpinner.getPreferredSize().height));
        if (thresholdSpinner.getEditor() instanceof JSpinner.DefaultEditor) {
            JFormattedTextField textField =
                    ((JSpinner.DefaultEditor) thresholdSpinner.getEditor()).getTextField();
            textField.setColumns(7);
        }
        thresholdSpinner.addChangeListener(e ->
                wallThresholdConfig.setThresholdFloor(((Number) thresholdSpinner.getValue()).intValue()));
        wallThresholdPanel.add(thresholdSpinner, thresholdGbc);
        add(wallThresholdPanel, gbc);

        gbc.gridy++;
        JCheckBox filledExecutionMarkersCheckbox = new JCheckBox(
                "Persistent Filled Execution Labels (otherwise 30 seconds)",
                config.isEnabled(IndicatorConfig.FILLED_EXECUTION_MARKERS));
        filledExecutionMarkersCheckbox.addActionListener(e ->
                config.setEnabled(IndicatorConfig.FILLED_EXECUTION_MARKERS, filledExecutionMarkersCheckbox.isSelected()));
        add(filledExecutionMarkersCheckbox, gbc);

        gbc.gridy++;
        JCheckBox fireKeyboardEventCheckbox = new JCheckBox(
                "Chart Hotkeys (B/S, A/W, C/F/Q/P, G/H/T, Z/Space, digits/numpad/M)",
                config.isEnabled(IndicatorConfig.FIRE_KEYBOARD_EVENT));
        fireKeyboardEventCheckbox.addActionListener(e ->
                config.setEnabled(IndicatorConfig.FIRE_KEYBOARD_EVENT, fireKeyboardEventCheckbox.isSelected()));
        add(fireKeyboardEventCheckbox, gbc);

        gbc.gridy++;
        JCheckBox tradingSound = new JCheckBox("Native Trading Notification Sound", config.isEnabled(IndicatorConfig.TRADING_NOTIFICATION_SOUND));
        tradingSound.addActionListener(e -> config.setEnabled(IndicatorConfig.TRADING_NOTIFICATION_SOUND, tradingSound.isSelected()));
        add(tradingSound, gbc);
        gbc.gridy++;
        javax.swing.JButton restart = new javax.swing.JButton("Restart Native Trading / Reload Secrets");
        restart.addActionListener(e -> RongPlugin.restartNativeTrading()); add(restart, gbc);
        gbc.gridy++;
        javax.swing.JButton openAuthorization = new javax.swing.JButton("Open Schwab Authorization"); openAuthorization.addActionListener(e -> RongPlugin.openNativeAuthorization()); add(openAuthorization, gbc);
        gbc.gridy++;
        javax.swing.JButton authorize = new javax.swing.JButton("Import Schwab Authorization Callback URL");
        authorize.addActionListener(e -> { String callback = javax.swing.JOptionPane.showInputDialog(this, "Paste the redirected Schwab authorization URL (containing code). It is exchanged locally and is never logged."); if (callback != null && !callback.isBlank()) RongPlugin.authorizeNativeTrading(callback); }); add(authorize, gbc);
        gbc.gridy++;
        javax.swing.JButton resetNative = new javax.swing.JButton("Reset Native Execution After Broker Review");
        resetNative.setToolTipText("Use only after checking unresolved orders at the broker. Does not resend orders.");
        resetNative.addActionListener(e -> RongPlugin.resetNativeExecutionAfterBrokerReview());
        add(resetNative, gbc);
    }

    private static JLabel connectionLabel() {
        JLabel label = new JLabel();
        label.setOpaque(true);
        label.setForeground(java.awt.Color.WHITE);
        label.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 8, 5, 8));
        return label;
    }

    private static void updateConnectionLabel(JLabel label, String name, String value, boolean healthy) {
        label.setText(name + ": " + value);
        label.setBackground(healthy ? new java.awt.Color(38, 139, 88) : new java.awt.Color(180, 62, 62));
        label.setToolTipText("Native connection status. Details are also written to the action log.");
    }
}
