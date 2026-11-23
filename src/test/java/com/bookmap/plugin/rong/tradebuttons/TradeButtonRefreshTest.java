package com.bookmap.plugin.rong.tradebuttons;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradeButtonRefreshTest {
    @Test void unchangedAccountRefreshPreservesPressedButtonAndSingleClick() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel panel = new JPanel(); AtomicInteger clicks = new AtomicInteger(), renders = new AtomicInteger();
            TradeButtonRefresh refresh = new TradeButtonRefresh(() -> TradeButtonRefresh.pressed(panel), groups -> {
                renders.incrementAndGet(); panel.removeAll(); panel.add(button(clicks));
            });
            try {
                refresh.request(config("AMD", "25 sh"));
                JButton original = (JButton) panel.getComponent(0);
                press(original);
                assertTrue(original.getModel().isPressed());
                // Account refresh carries a newly parsed but identical config.
                refresh.request(config("AMD", "25 sh"));
                assertSame(original, panel.getComponent(0));
                release((JButton) panel.getComponent(0));
                assertEquals(1, clicks.get()); assertEquals(1, renders.get());
            } finally { refresh.stop(); }
        });
    }

    @Test void changedConfigurationWaitsForReleaseAndCoalescesToLatest() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel panel = new JPanel(); AtomicInteger clicks = new AtomicInteger(), renders = new AtomicInteger();
            List<List<TradebookButtonGroup>> applied = new ArrayList<>();
            TradeButtonRefresh refresh = new TradeButtonRefresh(() -> TradeButtonRefresh.pressed(panel), groups -> {
                applied.add(groups); renders.incrementAndGet(); panel.removeAll(); panel.add(button(clicks));
            });
            try {
                refresh.request(config("AMD", "25 sh")); JButton original = (JButton) panel.getComponent(0);
                press(original);
                refresh.request(config("AMD", "50 sh"));
                refresh.request(config("AMD", "75 sh"));
                refresh.flush(); assertSame(original, panel.getComponent(0));
                release(original); assertEquals(1, clicks.get());
                refresh.flush(); assertNotSame(original, panel.getComponent(0));
                assertEquals(2, renders.get()); assertEquals(config("AMD", "75 sh"), applied.get(1));
            } finally { refresh.stop(); }
        });
    }

    @Test void replacingButtonBetweenPressAndReleaseReproducesTheLostClick() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicInteger clicks = new AtomicInteger(); JPanel panel = new JPanel();
            JButton original = button(clicks); panel.add(original); press(original);
            panel.removeAll(); JButton replacement = button(clicks); panel.add(replacement);
            release(replacement);
            assertEquals(0, clicks.get(), "Release on a rebuilt button never activates the original press");
        });
    }

    @Test void tracesPressActivationAndCancelledReleaseWithoutSubmittingAgain() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicInteger clicks = new AtomicInteger(); JButton button = button(clicks);
            List<String> logs = new ArrayList<>(); ButtonGestureTrace.attach(button, "AMD 25 sh", logs::add);
            press(button); release(button);
            assertEquals(1, clicks.get());
            assertTrue(logs.get(0).startsWith("Button mouse-down"));
            assertTrue(logs.stream().anyMatch(value -> value.startsWith("Button activated")));
            assertTrue(logs.get(logs.size() - 1).endsWith("activated=true"));
            logs.clear(); press(button); button.getModel().setArmed(false); release(button);
            assertEquals(1, clicks.get());
            assertTrue(logs.stream().anyMatch(value -> value.startsWith("Button gesture cancelled")));
        });
    }

    private static List<TradebookButtonGroup> config(String symbol, String method) {
        return List.of(new TradebookButtonGroup(symbol + ":short", "Short", false, "short", "Short", List.of(method)));
    }
    private static JButton button(AtomicInteger clicks) {
        JButton button = new JButton("25 sh"); button.setSize(100, 40);
        button.addActionListener(event -> clicks.incrementAndGet()); return button;
    }
    private static void press(JButton button) {
        button.dispatchEvent(new MouseEvent(button, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                InputEvent.BUTTON1_DOWN_MASK, 20, 20, 1, false, MouseEvent.BUTTON1));
    }
    private static void release(JButton button) {
        button.dispatchEvent(new MouseEvent(button, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                0, 20, 20, 1, false, MouseEvent.BUTTON1));
    }
}
