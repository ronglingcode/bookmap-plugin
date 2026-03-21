package com.bookmap.plugin.common;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CanvasIcon;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CompositeCoordinateBase;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CompositeHorizontalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CompositeVerticalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.PreparedImage;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory.ScreenSpaceCanvasType;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterAdapter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterFactory;

/**
 * Draws horizontal price lines on Bookmap charts using ScreenSpaceCanvas.
 * Lines track price in data coordinates so they automatically follow scroll/zoom.
 */
public class PriceLinePainter implements ScreenSpacePainterFactory {

    private static final int LINE_IMAGE_WIDTH = 5000;
    private static final int LINE_IMAGE_HEIGHT = 20; // enough for line + label text
    private static final int LINE_THICKNESS = 2;
    private static final Font LABEL_FONT = new Font("SansSerif", Font.BOLD, 11);

    private final PriceLineStore store;

    /** Map painter alias → instrument alias. Set when painter is created. */
    private final Map<String, String> painterToInstrument = new ConcurrentHashMap<>();
    private volatile String lastRegisteredInstrument;

    public PriceLinePainter(PriceLineStore store) {
        this.store = store;
    }

    public void registerInstrument(String instrumentAlias) {
        lastRegisteredInstrument = instrumentAlias;
    }

    public void unregisterInstrument(String instrumentAlias) {
        painterToInstrument.entrySet().removeIf(e -> e.getValue().equals(instrumentAlias));
    }

    @Override
    public ScreenSpacePainter createScreenSpacePainter(String alias, String fullName,
                                                        ScreenSpaceCanvasFactory canvasFactory) {
        ScreenSpaceCanvas canvas = canvasFactory.createCanvas(ScreenSpaceCanvasType.HEATMAP);

        if (lastRegisteredInstrument != null) {
            painterToInstrument.put(alias, lastRegisteredInstrument);
        }
        String instrumentAlias = painterToInstrument.getOrDefault(alias, lastRegisteredInstrument);

        System.out.println("[PriceLinePainter] Created painter: " + alias + " → " + instrumentAlias);

        return new PainterInstance(alias, instrumentAlias, canvas);
    }

    /**
     * One painter instance per chart. Manages CanvasIcon shapes for its instrument's lines.
     */
    private class PainterInstance implements ScreenSpacePainterAdapter, PriceLineStore.ChangeListener {

        private final String painterAlias;
        private final String instrumentAlias;
        private final ScreenSpaceCanvas canvas;

        /** Currently displayed shapes: lineId → CanvasIcon */
        private final Map<String, CanvasIcon> activeShapes = new ConcurrentHashMap<>();

        private volatile int fullPixelsWidth;

        PainterInstance(String painterAlias, String instrumentAlias, ScreenSpaceCanvas canvas) {
            this.painterAlias = painterAlias;
            this.instrumentAlias = instrumentAlias;
            this.canvas = canvas;
            store.addListener(this);
        }

        @Override
        public void onHeatmapFullPixelsWidth(int width) {
            this.fullPixelsWidth = width;
            rebuildLines();
        }

        @Override
        public void onLinesChanged(String changedInstrument) {
            if (instrumentAlias != null && instrumentAlias.equals(changedInstrument)) {
                rebuildLines();
            }
        }

        private void rebuildLines() {
            if (instrumentAlias == null) return;

            // Remove all existing shapes
            for (CanvasIcon icon : activeShapes.values()) {
                canvas.removeShape(icon);
            }
            activeShapes.clear();

            // Add shapes for current lines
            List<PriceLine> lines = store.getLines(instrumentAlias);
            for (PriceLine line : lines) {
                CanvasIcon icon = createLineIcon(line);
                if (icon != null) {
                    canvas.addShape(icon);
                    activeShapes.put(line.getId(), icon);
                }
            }
        }

        private CanvasIcon createLineIcon(PriceLine line) {
            int width = Math.max(fullPixelsWidth, LINE_IMAGE_WIDTH);
            BufferedImage img = renderLineImage(line, width);
            PreparedImage prepared = new PreparedImage(img);

            // Y: data coordinate at the price level (in ticks)
            // Place the image centered on the price line
            CompositeVerticalCoordinate y1 = new CompositeVerticalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, -LINE_IMAGE_HEIGHT / 2, line.getPriceInTicks());
            CompositeVerticalCoordinate y2 = new CompositeVerticalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, LINE_IMAGE_HEIGHT / 2, line.getPriceInTicks());

            // X: span from pixel 0 to full width
            CompositeHorizontalCoordinate x1 = new CompositeHorizontalCoordinate(
                    CompositeCoordinateBase.PIXEL_ZERO, 0, 0);
            CompositeHorizontalCoordinate x2 = new CompositeHorizontalCoordinate(
                    CompositeCoordinateBase.PIXEL_ZERO, width, 0);

            return new CanvasIcon(prepared, x1, y1, x2, y2);
        }

        private BufferedImage renderLineImage(PriceLine line, int width) {
            BufferedImage img = new BufferedImage(width, LINE_IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color color = line.getType().color;
            int lineY = LINE_IMAGE_HEIGHT / 2;

            // Draw the horizontal line
            g.setColor(color);
            if (line.getType() == PriceLine.LineType.ENTRY) {
                g.setStroke(new BasicStroke(LINE_THICKNESS));
            } else {
                // Dashed for stop loss and take profit
                g.setStroke(new BasicStroke(LINE_THICKNESS, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10.0f, new float[]{8.0f, 6.0f}, 0.0f));
            }
            g.drawLine(0, lineY, width, lineY);

            // Draw label with background
            String label = line.getType().label + " " + String.format("%.2f", line.getRealPrice());
            g.setFont(LABEL_FONT);
            FontMetrics fm = g.getFontMetrics();
            int textWidth = fm.stringWidth(label);
            int textHeight = fm.getHeight();
            int padding = 4;

            int labelX = 10;
            int labelY = lineY - textHeight / 2 - 1;

            // Background rectangle
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 180));
            g.fillRect(labelX - padding, labelY, textWidth + padding * 2, textHeight + 2);

            // Label text
            g.setColor(Color.WHITE);
            g.drawString(label, labelX, lineY + fm.getAscent() / 2 - 1);

            g.dispose();
            return img;
        }

        @Override
        public void dispose() {
            store.removeListener(this);
            for (CanvasIcon icon : activeShapes.values()) {
                canvas.removeShape(icon);
            }
            activeShapes.clear();
            canvas.dispose();
            painterToInstrument.remove(painterAlias);
            System.out.println("[PriceLinePainter] Disposed painter: " + painterAlias);
        }
    }
}
