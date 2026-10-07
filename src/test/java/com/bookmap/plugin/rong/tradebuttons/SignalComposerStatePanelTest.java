package com.bookmap.plugin.rong.tradebuttons;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import com.bookmap.plugin.rong.patterns.*;
import com.bookmap.plugin.rong.signal.SignalComposerInspection;

class SignalComposerStatePanelTest {
    static <T> List<T> descendants(Container parent, Class<T> type) {
        List<T> results = new ArrayList<>();
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) results.add(type.cast(child));
            if (child instanceof Container) results.addAll(descendants((Container)child, type));
        }
        return results;
    }

    @Test void perStockPanelsRefreshIndependentlyAndKeepSelectionOnUnchangedReplayState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<SignalComposerInspection> state = new AtomicReference<>(
                    new SignalComposerInspection("", "AMD Enabled\nScanning", List.of()));
            SignalComposerStatePanel amd = new SignalComposerStatePanel(state::get);
            SignalComposerStatePanel other = new SignalComposerStatePanel(() ->
                    new SignalComposerInspection("Disabled", "OTHER Disabled", List.of()));
            List<JTextArea> areas = descendants(amd, JTextArea.class);
            JTextArea text = areas.get(areas.size() - 1);
            assertFalse(text.isEditable()); assertTrue(text.getLineWrap());
            text.select(0, 3); amd.refresh(); assertEquals("AMD", text.getSelectedText());
            state.set(new SignalComposerInspection("Waiting for snapshot", "AMD Enabled\nWaiting for snapshot", List.of()));
            amd.refresh(); assertTrue(text.getText().contains("Waiting for snapshot"));
            List<JTextArea> otherAreas = descendants(other, JTextArea.class);
            assertEquals("OTHER Disabled", otherAreas.get(otherAreas.size() - 1).getText());
            JToggleButton toggle = descendants(amd, JToggleButton.class).get(0);
            assertFalse(text.getParent().getParent().isVisible());
            toggle.doClick(); assertTrue(text.getParent().getParent().isVisible());
        });
    }

    @Test void directionsHaveSeparateEventLinesWithFullNamesAndExpiredStyling() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            long time = java.time.Instant.parse("2026-10-06T13:31:52Z").getEpochSecond() * 1_000_000_000L;
            PatternEvent bid = PatternEvent.builder("AMD", 1, PatternEventType.BIDS_CANCELLED, "bid")
                    .size(1465, PatternEvent.SizeBasis.DISPLAYED_WALL).price(648, 1).times(time, time).build();
            PatternEvent offer = PatternEvent.builder("AMD", 1, PatternEventType.OFFER_BREAKOUT, "offer")
                    .size(60000, PatternEvent.SizeBasis.DISPLAYED_WALL).price(64825, .01).times(time, time).build();
            SignalComposerInspection snapshot = new SignalComposerInspection("", "Scanning", List.of(
                    new SignalComposerInspection.Section(Direction.LONG, List.of(),
                            List.of(new SignalComposerInspection.Event(bid, "Expired")),
                            List.of(new SignalComposerInspection.Event(offer, "Out of range"))),
                    new SignalComposerInspection.Section(Direction.SHORT, List.of(), List.of(), List.of())));
            SignalComposerStatePanel panel = new SignalComposerStatePanel(() -> snapshot);
            List<JTextArea> lines = descendants(panel, JTextArea.class).stream()
                    .filter(area -> "patternEvent".equals(area.getName())).collect(java.util.stream.Collectors.toList());
            assertEquals(8, lines.size());
            assertEquals("09:31:52: 1.4K @ 648, Bids cancelled", lines.get(0).getText());
            assertNotEquals(java.awt.Color.WHITE, lines.get(0).getForeground());
            assertEquals("09:31:52: 60K @ 648.25, Offer breakout", lines.get(2).getText());
            assertTrue(lines.get(2).getToolTipText().contains("Out of range"));
            assertTrue(lines.get(0).getToolTipText().contains("1465 lots"));
            assertEquals(2, lines.stream().filter(Component::isVisible).count());
            assertTrue(descendants(panel, JTable.class).isEmpty());
            assertTrue(descendants(panel, javax.swing.table.JTableHeader.class).isEmpty());
            lines.get(0).select(0, 8);
            panel.refresh(); assertEquals("09:31:52", lines.get(0).getSelectedText());
        });
    }
    @Test void quantitiesUseCompactLotsWithoutRoundingUp() {
        assertEquals("999", SignalComposerStatePanel.compactLots(999));
        assertEquals("1K", SignalComposerStatePanel.compactLots(1000));
        assertEquals("1.4K", SignalComposerStatePanel.compactLots(1465));
        assertEquals("1.4K", SignalComposerStatePanel.compactLots(1471));
        assertEquals("999.9K", SignalComposerStatePanel.compactLots(999999));
        assertEquals("1.4M", SignalComposerStatePanel.compactLots(1465000));
        assertEquals("1B", SignalComposerStatePanel.compactLots(1000000000));
    }

    static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) if (child instanceof Container) layout((Container)child);
    }

    @Test void readOnlyCaretDoesNotScrollOrValidateAncestorsDuringUpdates() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            java.util.concurrent.atomic.AtomicInteger scrollRequests = new java.util.concurrent.atomic.AtomicInteger();
            JTextArea text = new JTextArea() {
                @Override public void scrollRectToVisible(java.awt.Rectangle rectangle) {
                    scrollRequests.incrementAndGet();
                }
            };
            SignalComposerStatePanel.InspectionCaret caret = new SignalComposerStatePanel.InspectionCaret();
            text.setCaret(caret);
            text.setText("09:31:52: 1.4K @ 648, Bids cancelled");
            text.select(0, 8);
            caret.adjustVisibility(new java.awt.Rectangle(100, 100, 1, 20));
            assertEquals(0, scrollRequests.get());
            assertEquals("09:31:52", text.getSelectedText());
            assertEquals(javax.swing.text.DefaultCaret.NEVER_UPDATE, caret.getUpdatePolicy());
            SignalComposerStatePanel panel = new SignalComposerStatePanel(() ->
                    new SignalComposerInspection("", "Diagnostics", List.of()));
            assertTrue(descendants(panel, JTextArea.class).stream().allMatch(area ->
                    area.getCaret() instanceof SignalComposerStatePanel.InspectionCaret));
        });
    }

    @Test void preferredSizeQueriesDuringRefreshAndResizeDoNotInvalidateBoxLayoutCalculations() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PatternEvent pattern = PatternEvent.builder("AMD", 1, PatternEventType.OFFER_SIZE_INCREASING_REJECTION, "offer")
                    .size(1465, PatternEvent.SizeBasis.DISPLAYED_WALL).price(648, 1).times(100, 100).build();
            List<SignalComposerInspection.Event> events = List.of(new SignalComposerInspection.Event(pattern, "Expired"));
            AtomicReference<SignalComposerInspection> snapshot = new AtomicReference<>(new SignalComposerInspection("", "", List.of()));
            SignalComposerStatePanel panel = new SignalComposerStatePanel(snapshot::get);
            for (JTextArea line : descendants(panel, JTextArea.class)) {
                if (!"patternEvent".equals(line.getName())) continue;
                line.setUI(new javax.swing.plaf.basic.BasicTextAreaUI() {
                    @Override public java.awt.Dimension getPreferredSize(JComponent component) {
                        // A host look and feel may size its text view during measurement.
                        if (component.getWidth() == 0) component.setSize(100, 30);
                        return super.getPreferredSize(component);
                    }
                });
            }
            for (int iteration = 0; iteration < 40; iteration++) {
                snapshot.set(new SignalComposerInspection("", "", iteration % 3 == 0 ? List.of() : List.of(
                        new SignalComposerInspection.Section(Direction.LONG, List.of(), events, events),
                        new SignalComposerInspection.Section(Direction.SHORT, List.of(), events, events))));
                panel.refresh();
                panel.setSize(iteration % 2 == 0 ? 320 : 520, 460);
                // Force fresh text measurement while its ancestors are computing preferred sizes,
                // matching JScrollPane/JViewport validation on initial display and resize.
                for (JTextArea line : descendants(panel, JTextArea.class)) {
                    if ("patternEvent".equals(line.getName())) line.setSize(0, 0);
                }
                Container content = (Container)((JScrollPane)panel.getComponent(0)).getViewport().getView();
                content.invalidate();
                assertDoesNotThrow(content::getPreferredSize);
                assertDoesNotThrow(() -> layout(panel));
            }
        });
    }

    @Test void eventLinesFitViewportAndWrapLongNamesWithoutHorizontalScrolling() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PatternEvent pattern = PatternEvent.builder("AMD", 1, PatternEventType.OFFER_SIZE_INCREASING_REJECTION, "offer")
                    .size(1465, PatternEvent.SizeBasis.DISPLAYED_WALL).price(648, 1).times(100, 100).build();
            List<SignalComposerInspection.Event> events = List.of(new SignalComposerInspection.Event(pattern, "Expired"));
            SignalComposerStatePanel panel = new SignalComposerStatePanel(() -> new SignalComposerInspection("", "", List.of(
                    new SignalComposerInspection.Section(Direction.LONG, List.of(), events, events),
                    new SignalComposerInspection.Section(Direction.SHORT, List.of(), events, events))));
            for (int width : new int[]{320, 420, 520}) {
                panel.setSize(width, 460);
                for (int pass = 0; pass < 4; pass++) layout(panel);
                JScrollPane scroll = (JScrollPane)panel.getComponent(0);
                assertEquals(scroll.getViewport().getWidth(), scroll.getViewport().getView().getWidth());
                assertFalse(scroll.getHorizontalScrollBar().isVisible());
                for (JTextArea line : descendants(panel, JTextArea.class)) {
                    if (!"patternEvent".equals(line.getName()) || !line.isVisible()) continue;
                    assertTrue(line.getText().endsWith("1.4K @ 648, Offer size increasing rejection"));
                    assertTrue(line.getLineWrap());
                    assertTrue(line.getHeight() >= line.getPreferredSize().height,
                            "panel " + width + " line " + line.getSize() + " preferred " + line.getPreferredSize());
                    assertTrue(line.getWidth() <= scroll.getViewport().getWidth());
                }
            }
        });
    }
}
