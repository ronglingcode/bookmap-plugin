package com.bookmap.plugin.rong.tradebuttons;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.*;
import javax.swing.text.DefaultCaret;
import com.bookmap.plugin.rong.patterns.Direction;
import com.bookmap.plugin.rong.signal.SignalComposerInspection;

/** Per-symbol advisory summary with bounded event groups and optional copyable diagnostics. */
final class SignalComposerStatePanel extends JPanel {
    private static final Color BACKGROUND = new Color(24, 35, 46);
    private static final Color MUTED = new Color(185, 199, 210);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
            .withZone(ZoneId.of("America/New_York"));
    private final Supplier<SignalComposerInspection> supplier;
    private final JLabel notice = new JLabel();
    private final JTextArea diagnostics = textArea();
    private final Map<Direction, DirectionPanel> directions = new EnumMap<>(Direction.class);
    private final JScrollPane scroll;

    SignalComposerStatePanel(Supplier<SignalComposerInspection> supplier) {
        super(new BorderLayout());
        this.supplier = supplier;
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder("Signal Composer"),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        JPanel content = new ViewportContent();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(BACKGROUND);
        notice.setFont(new Font("SansSerif", Font.BOLD, 14));
        notice.setForeground(new Color(255, 205, 120));
        notice.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        notice.setAlignmentX(LEFT_ALIGNMENT);
        content.add(notice);
        for (Direction direction : Direction.values()) {
            DirectionPanel panel = new DirectionPanel(direction);
            directions.put(direction, panel);
            content.add(panel);
        }
        JToggleButton toggle = new JToggleButton("▸ Diagnostics");
        toggle.setAlignmentX(LEFT_ALIGNMENT);
        content.add(toggle);
        JScrollPane diagnosticScroll = new JScrollPane(diagnostics);
        diagnosticScroll.setPreferredSize(new Dimension(480, 180));
        diagnosticScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 180));
        diagnosticScroll.setAlignmentX(LEFT_ALIGNMENT);
        diagnosticScroll.setVisible(false);
        content.add(diagnosticScroll);
        toggle.addActionListener(event -> {
            diagnosticScroll.setVisible(toggle.isSelected());
            toggle.setText(toggle.isSelected() ? "▾ Diagnostics" : "▸ Diagnostics");
            content.revalidate();
            content.repaint();
        });
        scroll = new JScrollPane(content);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(500, 440));
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        scroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) {
                // Resize notifications run after layout; never invalidate layouts from setBounds.
                updateEventLineHeights();
            }
        });
        add(scroll, BorderLayout.CENTER);
        refresh();
    }

    void refresh() {
        SignalComposerInspection snapshot = supplier.get();
        Point position = scroll.getViewport().getViewPosition();
        notice.setText(snapshot.notice);
        notice.setVisible(!snapshot.notice.isEmpty());
        for (Direction direction : Direction.values()) {
            SignalComposerInspection.Section section = snapshot.sections.stream()
                    .filter(s -> s.direction == direction).findFirst().orElse(null);
            directions.get(direction).refresh(section);
        }
        updateText(diagnostics, snapshot.diagnostics);
        updateEventLineHeights();
        scroll.getViewport().setViewPosition(position);
    }

    private void updateEventLineHeights() {
        int width = Math.max(80, scroll.getViewport().getWidth() - 16);
        for (DirectionPanel direction : directions.values()) {
            direction.bids.updateLineHeights(width);
            direction.offers.updateLineHeights(width);
        }
    }

    private static JTextArea textArea() {
        JTextArea text = new JTextArea();
        text.setCaret(new InspectionCaret());
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(new Font("SansSerif", Font.PLAIN, 14));
        text.setBackground(BACKGROUND);
        text.setForeground(Color.WHITE);
        text.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        return text;
    }

    static String compactLots(long quantity) {
        long divisor = quantity >= 1_000_000_000L ? 1_000_000_000L : quantity >= 1_000_000L ? 1_000_000L : quantity >= 1000 ? 1000 : 1;
        if (divisor == 1) return Long.toString(quantity);
        String suffix = divisor == 1000 ? "K" : divisor == 1_000_000L ? "M" : "B";
        return java.math.BigDecimal.valueOf(quantity).divide(java.math.BigDecimal.valueOf(divisor), 1,
                java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString() + suffix;
    }

    /** Read-only inspection updates preserve the user's scroll position and selection. */
    static final class InspectionCaret extends DefaultCaret {
        InspectionCaret() { setUpdatePolicy(NEVER_UPDATE); }

        @Override protected void adjustVisibility(java.awt.Rectangle location) {
            // DefaultCaret schedules scrollRectToVisible after text/selection changes.
            // Avoid that deferred validation path; inspection is scrolled explicitly by the user.
        }
    }

    private static class ViewportContent extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) { return 20; }
        @Override public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) { return Math.max(20, visible.height - 20); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private static void updateText(JTextArea text, String value) {
        if (value.equals(text.getText())) return;
        JViewport viewport = text.getParent() instanceof JViewport ? (JViewport)text.getParent() : null;
        Point position = viewport == null ? null : viewport.getViewPosition();
        int start = text.getSelectionStart(), end = text.getSelectionEnd();
        text.setText(value);
        text.select(Math.min(start, value.length()), Math.min(end, value.length()));
        if (viewport != null) viewport.setViewPosition(position);
    }

    private static final class DirectionPanel extends JPanel {
        private final JTextArea requirements = textArea();
        private final EventGroup bids = new EventGroup("Bid patterns"), offers = new EventGroup("Offer patterns");

        DirectionPanel(Direction direction) {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(BACKGROUND);
            setAlignmentX(LEFT_ALIGNMENT);
            setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            JLabel heading = new JLabel(direction.name());
            heading.setFont(new Font("SansSerif", Font.BOLD, 16));
            heading.setForeground(direction == Direction.LONG ? new Color(130, 220, 180) : new Color(255, 165, 165));
            heading.setAlignmentX(LEFT_ALIGNMENT);
            add(heading);
            requirements.setAlignmentX(LEFT_ALIGNMENT);
            add(requirements);
            add(bids);
            add(offers);
        }

        void refresh(SignalComposerInspection.Section section) {
            String value = section == null ? "" : String.join("\n", section.requirements);
            updateText(requirements, value);
            requirements.setVisible(!value.isEmpty());
            bids.refresh(section == null ? List.of() : section.bids);
            offers.refresh(section == null ? List.of() : section.offers);
        }

        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    }

    static String eventLine(SignalComposerInspection.Event event) {
        String name = event.pattern.type.name().toLowerCase(Locale.US).replace('_', ' ');
        name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return TIME.format(Instant.ofEpochSecond(event.pattern.eventTimeNs / 1_000_000_000L,
                event.pattern.eventTimeNs % 1_000_000_000L)) + ": " + compactLots(event.pattern.size) + " @ "
                + java.math.BigDecimal.valueOf(event.pattern.price).stripTrailingZeros().toPlainString()
                + ", " + name;
    }

    private static final class EventGroup extends JPanel {
        private final JTextArea[] lines = new JTextArea[2];
        private final JLabel empty = new JLabel("No recent events");

        EventGroup(String title) {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(BACKGROUND);
            setAlignmentX(LEFT_ALIGNMENT);
            setBorder(BorderFactory.createEmptyBorder(8, 0, 4, 0));
            JLabel label = new JLabel(title);
            label.setFont(new Font("SansSerif", Font.BOLD, 14));
            label.setForeground(Color.WHITE);
            label.setAlignmentX(LEFT_ALIGNMENT);
            add(label);
            for (int index = 0; index < lines.length; index++) {
                JTextArea line = new JTextArea() {
                    @Override public Dimension getMaximumSize() {
                        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
                    }
                };
                line.setName("patternEvent");
                line.setCaret(new InspectionCaret());
                line.setEditable(false);
                line.setLineWrap(true);
                line.setWrapStyleWord(true);
                line.setFont(new Font("SansSerif", Font.PLAIN, 14));
                line.setBackground(BACKGROUND);
                line.setForeground(Color.WHITE);
                line.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 0));
                line.setAlignmentX(LEFT_ALIGNMENT);
                line.setVisible(false);
                lines[index] = line;
                add(line);
            }
            empty.setFont(new Font("SansSerif", Font.PLAIN, 14));
            empty.setForeground(MUTED);
            empty.setAlignmentX(LEFT_ALIGNMENT);
            add(empty);
        }

        void refresh(List<SignalComposerInspection.Event> events) {
            int count = Math.min(2, events.size());
            for (int index = 0; index < lines.length; index++) {
                JTextArea line = lines[index];
                line.setVisible(index < count);
                if (index >= count) continue;
                SignalComposerInspection.Event event = events.get(index);
                updateText(line, eventLine(event));
                line.setForeground(event.status.equals("Expired") ? new Color(125, 140, 150) : Color.WHITE);
                line.setToolTipText(event.pattern.size + " lots · New York market time"
                        + (event.status.isEmpty() ? "" : " · " + event.status));
            }
            empty.setVisible(count == 0);
            revalidate();
            repaint();
        }

        void updateLineHeights(int width) {
            for (JTextArea line : lines) {
                if (!line.isVisible()) continue;
                java.awt.FontMetrics metrics = line.getFontMetrics(line.getFont());
                int available = Math.max(1, width - 6);
                int rows = 1, used = 0;
                for (String word : line.getText().split(" ")) {
                    int size = metrics.stringWidth(word);
                    int gap = used == 0 ? 0 : metrics.charWidth(' ');
                    if (used > 0 && used + gap + size > available) {
                        rows++;
                        used = size;
                    } else used += gap + size;
                }
                Dimension preferred = new Dimension(1, rows * metrics.getHeight() + 8);
                if (!preferred.equals(line.getPreferredSize())) {
                    line.setPreferredSize(preferred);
                    line.setMinimumSize(preferred);
                }
            }
        }

        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    }
}
