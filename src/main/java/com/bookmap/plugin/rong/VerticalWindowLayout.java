package com.bookmap.plugin.rong;

import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.Scrollable;

/** Shared sizing for the narrow Trade and Logs windows. */
public final class VerticalWindowLayout {
    public static final int WIDTH = 285;
    public static final int HEIGHT = 1240;
    public static final int CONTENT_WIDTH = WIDTH - 40;

    private VerticalWindowLayout() { }

    public static void sizeToScreen(JFrame frame) {
        frame.setPreferredSize(new Dimension(WIDTH, HEIGHT));
        frame.pack();
        Rectangle bounds = new Rectangle(frame.getGraphicsConfiguration().getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(frame.getGraphicsConfiguration());
        bounds.x += insets.left;
        bounds.y += insets.top;
        bounds.width -= insets.left + insets.right;
        bounds.height -= insets.top + insets.bottom;
        frame.setSize(Math.min(WIDTH, bounds.width), Math.min(HEIGHT, bounds.height));
        frame.setLocation(Math.max(bounds.x, Math.min(frame.getX(), bounds.x + bounds.width - frame.getWidth())),
                Math.max(bounds.y, Math.min(frame.getY(), bounds.y + bounds.height - frame.getHeight())));
    }

    public static String wrapText(String text, int width) {
        return wrapHtml(text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>"), width);
    }

    public static String wrapHtml(String html, int width) {
        return "<html><table width='" + width + "' cellpadding='0' cellspacing='0'><tr><td>"
                + html + "</td></tr></table></html>";
    }

    /** Keep controls within the viewport while allowing their rows to scroll vertically. */
    public static final class Content extends JPanel implements Scrollable {
        @Override public Dimension getPreferredSize() {
            return new Dimension(CONTENT_WIDTH, super.getPreferredSize().height);
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(24, visible.height - 24);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
