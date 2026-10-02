package com.bookmap.plugin.rong;

import com.bookmap.plugin.rong.miniviteapp.core.account.ExecutionExports.Format;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import javax.swing.*;

/** One header button; choosing a format copies text without opening another window. */
final class ExecutionExportMenu {
    private ExecutionExportMenu() {}
    static JButton create(Component parent, Function<Format, String> export) {
        JButton button = new JButton("Export");
        button.setToolTipText("Copy today's cached account executions to the clipboard");
        JPopupMenu menu = new JPopupMenu();
        for (Format format : Format.values()) {
            JMenuItem item = new JMenuItem(format.label);
            item.addActionListener(event -> copy(parent, button, format, export)); menu.add(item);
        }
        button.addActionListener(event -> menu.show(button, 0, button.getHeight()));
        return button;
    }
    private static void copy(Component parent, JButton button, Format format, Function<Format, String> export) {
        button.setEnabled(false);
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception {
                String content = export.apply(format);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(content), null);
                return null;
            }
            protected void done() {
                button.setEnabled(true);
                try { get(); PluginLog.action("", "Export", "Copied " + format.label + " to clipboard"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                catch (ExecutionException error) { JOptionPane.showMessageDialog(parent, error.getCause().getMessage(), "Execution export failed", JOptionPane.ERROR_MESSAGE); }
            }
        }.execute();
    }
}
