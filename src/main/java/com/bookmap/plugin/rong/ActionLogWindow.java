package com.bookmap.plugin.rong;

import java.awt.BorderLayout;
import java.awt.Font;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * Small action-only log window. It intentionally stays separate from PluginLog's
 * verbose file logging so the UI only shows trading actions worth glancing at.
 */
public class ActionLogWindow {

    private static final int MAX_LINES = 20;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Deque<String> lines = new ArrayDeque<>();
    private static JFrame frame;
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

    public static void dispose() {
        SwingUtilities.invokeLater(() -> {
            lines.clear();
            if (frame != null) {
                frame.dispose();
                frame = null;
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
        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(new JScrollPane(textArea), BorderLayout.CENTER);
        frame.pack();
        frame.setVisible(true);
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
}
