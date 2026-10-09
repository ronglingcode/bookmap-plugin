package com.bookmap.plugin.rong.pricelines;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Canvas;
import java.awt.Component;
import java.awt.DefaultKeyboardFocusManager;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JTextArea;
import org.junit.jupiter.api.Test;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainter;

class ChartHotkeyCaptureTest {
    private static class FocusableChart extends JPanel {
        int focusRequests;
        @Override public boolean isShowing() { return true; }
        @Override public boolean requestFocusInWindow() { focusRequests++; return true; }
    }

    @Test
    void hoveringChartRestoresFocusFromAnotherPanelWithoutInterruptingEditing() {
        FocusableChart chart = new FocusableChart();
        chart.setFocusable(true);
        ChartHoverHotkeyHandler.focusHoveredChart(chart, new JPanel());
        assertEquals(1, chart.focusRequests);
        ChartHoverHotkeyHandler.focusHoveredChart(chart, chart);
        assertEquals(1, chart.focusRequests);
        ChartHoverHotkeyHandler.focusHoveredChart(chart, new JTextField());
        assertEquals(1, chart.focusRequests);
        JTextArea log = new JTextArea();
        log.setEditable(false);
        ChartHoverHotkeyHandler.focusHoveredChart(chart, log);
        assertEquals(2, chart.focusRequests);
        chart.setName("annotation editor");
        ChartHoverHotkeyHandler.focusHoveredChart(chart, new JPanel());
        assertEquals(2, chart.focusRequests);
    }

    @Test
    void nonFocusableChartSurfaceUsesFocusableParent() {
        FocusableChart chart = new FocusableChart();
        chart.setFocusable(true);
        JPanel surface = new JPanel();
        surface.setFocusable(false);
        chart.add(surface);
        ChartHoverHotkeyHandler.focusHoveredChart(surface, new JPanel());
        assertEquals(1, chart.focusRequests);
    }

    private static class TestFocusManager extends DefaultKeyboardFocusManager {
        List<KeyEventDispatcher> dispatchers() { return getKeyEventDispatchers(); }
    }

    @Test
    void stalePermanentTextFocusDoesNotBlockCurrentChartButActualEditingDoes() {
        KeyboardFocusManager original = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        Canvas chart = new Canvas();
        JTextField editor = new JTextField();
        Component[] focus = {chart};
        KeyboardFocusManager.setCurrentKeyboardFocusManager(new DefaultKeyboardFocusManager() {
            @Override public Component getFocusOwner() { return focus[0]; }
            @Override public Component getPermanentFocusOwner() { return editor; }
        });
        try {
            KeyEvent event = new KeyEvent(chart, KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(), 0, KeyEvent.VK_1, '1');
            assertFalse(ChartHoverHotkeyHandler.isTextEntryEvent(event));
            focus[0] = editor;
            assertTrue(ChartHoverHotkeyHandler.isTextEntryEvent(event));
            focus[0] = chart;
            KeyEvent typing = new KeyEvent(editor, KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(), 0, KeyEvent.VK_1, '1');
            assertTrue(ChartHoverHotkeyHandler.isTextEntryEvent(typing));
            event.consume();
            assertTrue(ChartHoverHotkeyHandler.isTextEntryEvent(event));
        } finally {
            KeyboardFocusManager.setCurrentKeyboardFocusManager(original);
        }
    }

    @Test
    void digitPressesAreCapturedOnceBeforeDownstreamConsumptionAndListenerIsRemoved() {
        KeyboardFocusManager original = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        TestFocusManager manager = new TestFocusManager();
        ChartHoverHotkeyHandler.removeAwtListener();
        KeyboardFocusManager.setCurrentKeyboardFocusManager(manager);
        try {
            int[] presses = {0};
            new ChartHoverHotkeyHandler(null, null) {
                @Override void handleChartHotkey(KeyEvent event, String key) {
                    assertFalse(event.isConsumed());
                    assertTrue(List.of("1", "2", "3", "4").contains(key));
                    presses[0]++;
                }
            };
            // A second instance must not register a duplicate dispatcher.
            new ChartHoverHotkeyHandler(null, null);
            assertEquals(1, manager.dispatchers().size());
            KeyEventDispatcher downstream = event -> { event.consume(); return true; };
            manager.addKeyEventDispatcher(downstream);
            for (int key = KeyEvent.VK_1; key <= KeyEvent.VK_4; key++) {
                KeyEvent event = new KeyEvent(new Canvas(), KeyEvent.KEY_PRESSED,
                        System.currentTimeMillis(), 0, key, KeyEvent.CHAR_UNDEFINED);
                for (KeyEventDispatcher dispatcher : manager.dispatchers()) {
                    if (dispatcher.dispatchKeyEvent(event)) break;
                }
                assertTrue(event.isConsumed());
            }
            assertEquals(4, presses[0]);
            ChartHoverHotkeyHandler.removeAwtListener();
            assertEquals(List.of(downstream), manager.dispatchers());
        } finally {
            ChartHoverHotkeyHandler.removeAwtListener();
            KeyboardFocusManager.setCurrentKeyboardFocusManager(original);
        }
    }

    @Test
    void temporaryMissingPriceKeepsHoverAndRetriesFreshMappingWithoutMouseMovement() throws Exception {
        ChartHoverHotkeyHandler handler = new ChartHoverHotkeyHandler(null, null);
        try {
            handler.registerSymbol("AAPL", 0.01);
            ScreenSpacePainter painter = handler.createScreenSpacePainter("hoverHotkey_AAPL", "AAPL", null);
            JPanel chart = new JPanel();
            chart.setSize(100, 100);
            ChartHoverHotkeyHandler.HoverContext hover =
                    new ChartHoverHotkeyHandler.HoverContext("AAPL", 99.0, chart);
            Field field = ChartHoverHotkeyHandler.class.getDeclaredField("lastHoverContext");
            field.setAccessible(true);
            field.set(null, hover);
            Point pointer = new Point(50, 50);
            assertNull(ChartHoverHotkeyHandler.resolveHoverContextAtPointer(hover, pointer, true));
            assertSame(hover, field.get(null));

            painter.onHeatmapPriceBottom(1000);
            painter.onHeatmapPriceHeight(100);
            painter.onHeatmapPixelsBottom(0);
            painter.onHeatmapPixelsHeight(100);
            ChartHoverHotkeyHandler.HoverContext recovered = ChartHoverHotkeyHandler.resolveHoverContextAtPointer(
                    (ChartHoverHotkeyHandler.HoverContext) field.get(null), pointer, true);
            assertNotNull(recovered);
            assertEquals("AAPL", recovered.instrument);
            assertEquals(10.5, recovered.price);
            assertSame(recovered, field.get(null));
            assertNull(ChartHoverHotkeyHandler.resolveHoverContextAtPointer(recovered, new Point(150, 50), true));
        } finally {
            ChartHoverHotkeyHandler.removeAwtListener();
        }
    }
}
