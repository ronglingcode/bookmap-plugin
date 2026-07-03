package com.bookmap.plugin.rong.pricelines;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.bookmap.plugin.rong.PluginLog;
import com.bookmap.plugin.rong.SymbolUtils;

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
 * Draws horizontal price zones on Bookmap charts using ScreenSpaceCanvas.
 */
public class PriceZonePainter implements ScreenSpacePainterFactory {

    public static final String PAINTER_NAME_PREFIX = "priceZones_";

    private static final int ZONE_IMAGE_WIDTH = 5000;
    private static final int EDGE_IMAGE_HEIGHT = 20;
    private static final int FILL_ALPHA = 24;
    private static final int EDGE_ALPHA = 170;
    private static final int LABEL_BACKGROUND_ALPHA = 160;
    private static final Font LABEL_FONT = new Font("SansSerif", Font.BOLD, 11);

    private final PriceZoneStore store;
    private final Map<String, String> painterToInstrument = new ConcurrentHashMap<>();
    private volatile String lastRegisteredInstrument;

    public PriceZonePainter(PriceZoneStore store) {
        this.store = store;
    }

    public void registerInstrument(String instrumentAlias) {
        lastRegisteredInstrument = SymbolUtils.cleanSymbol(instrumentAlias);
    }

    public void unregisterInstrument(String instrumentAlias) {
        String cleanInstrumentAlias = SymbolUtils.cleanSymbol(instrumentAlias);
        painterToInstrument.entrySet().removeIf(e -> e.getValue().equals(cleanInstrumentAlias));
    }

    @Override
    public ScreenSpacePainter createScreenSpacePainter(String alias, String fullName,
                                                       ScreenSpaceCanvasFactory canvasFactory) {
        ScreenSpaceCanvas canvas = canvasFactory.createCanvas(ScreenSpaceCanvasType.HEATMAP);

        String instrumentAlias = resolveInstrumentAlias(alias);
        if (instrumentAlias != null) {
            painterToInstrument.put(alias, instrumentAlias);
        }

        PluginLog.info("[PriceZonePainter] Created painter: " + alias + " -> " + instrumentAlias);
        return new PainterInstance(alias, instrumentAlias, canvas);
    }

    private String resolveInstrumentAlias(String painterAlias) {
        String existingInstrument = painterToInstrument.get(painterAlias);
        if (existingInstrument != null) {
            return existingInstrument;
        }
        if (painterAlias != null) {
            int prefixIndex = painterAlias.lastIndexOf(PAINTER_NAME_PREFIX);
            if (prefixIndex >= 0) {
                String instrumentAlias = painterAlias.substring(prefixIndex + PAINTER_NAME_PREFIX.length());
                if (!instrumentAlias.isEmpty()) {
                    return SymbolUtils.cleanSymbol(instrumentAlias);
                }
            }
        }
        return lastRegisteredInstrument;
    }

    private class PainterInstance implements ScreenSpacePainterAdapter, PriceZoneStore.ChangeListener {

        private final String painterAlias;
        private final String instrumentAlias;
        private final ScreenSpaceCanvas canvas;
        private final List<CanvasIcon> activeShapes = new ArrayList<>();
        private final Object shapeLock = new Object();

        private volatile int fullPixelsWidth;
        private boolean disposed;

        PainterInstance(String painterAlias, String instrumentAlias, ScreenSpaceCanvas canvas) {
            this.painterAlias = painterAlias;
            this.instrumentAlias = instrumentAlias;
            this.canvas = canvas;
            store.addListener(this);
        }

        @Override
        public void onHeatmapFullPixelsWidth(int width) {
            this.fullPixelsWidth = width;
            rebuildZones();
        }

        @Override
        public void onZonesChanged(String changedInstrument) {
            if (instrumentAlias != null && instrumentAlias.equals(changedInstrument)) {
                rebuildZones();
            }
        }

        private void rebuildZones() {
            synchronized (shapeLock) {
                if (disposed || instrumentAlias == null) {
                    return;
                }
                removeActiveShapesLocked();

                List<PriceZone> zones = store.getZones(instrumentAlias);
                for (PriceZone zone : zones) {
                    for (CanvasIcon icon : createZoneIcons(zone)) {
                        canvas.addShape(icon);
                        activeShapes.add(icon);
                    }
                }
            }
        }

        private List<CanvasIcon> createZoneIcons(PriceZone zone) {
            int width = Math.max(fullPixelsWidth, ZONE_IMAGE_WIDTH);
            List<CanvasIcon> icons = new ArrayList<>();
            CanvasIcon fillIcon = createFillIcon(zone, width);
            if (fillIcon != null) {
                icons.add(fillIcon);
            }
            icons.add(createEdgeIcon(zone, width, zone.getHighPriceInTicks(), true));
            icons.add(createEdgeIcon(zone, width, zone.getLowPriceInTicks(), true));
            return icons;
        }

        private CanvasIcon createFillIcon(PriceZone zone, int width) {
            if (zone.getHighPriceInTicks() <= zone.getLowPriceInTicks()) {
                return null;
            }
            BufferedImage image = renderFillImage(zone.getColor(), width);
            PreparedImage prepared = new PreparedImage(image);

            CompositeHorizontalCoordinate x1 = new CompositeHorizontalCoordinate(
                    CompositeCoordinateBase.PIXEL_ZERO, 0, 0);
            CompositeHorizontalCoordinate x2 = new CompositeHorizontalCoordinate(
                    CompositeCoordinateBase.PIXEL_ZERO, width, 0);
            CompositeVerticalCoordinate y1 = new CompositeVerticalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, 0, zone.getHighPriceInTicks());
            CompositeVerticalCoordinate y2 = new CompositeVerticalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, 0, zone.getLowPriceInTicks());

            return new CanvasIcon(prepared, x1, y1, x2, y2);
        }

        private CanvasIcon createEdgeIcon(PriceZone zone, int width, double priceInTicks, boolean showLabel) {
            BufferedImage image = renderEdgeImage(zone, width, showLabel);
            PreparedImage prepared = new PreparedImage(image);

            CompositeHorizontalCoordinate x1 = new CompositeHorizontalCoordinate(
                    CompositeCoordinateBase.PIXEL_ZERO, 0, 0);
            CompositeHorizontalCoordinate x2 = new CompositeHorizontalCoordinate(
                    CompositeCoordinateBase.PIXEL_ZERO, width, 0);
            CompositeVerticalCoordinate y1 = new CompositeVerticalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, -EDGE_IMAGE_HEIGHT / 2, priceInTicks);
            CompositeVerticalCoordinate y2 = new CompositeVerticalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, EDGE_IMAGE_HEIGHT / 2, priceInTicks);

            return new CanvasIcon(prepared, x1, y1, x2, y2);
        }

        private BufferedImage renderFillImage(Color color, int width) {
            BufferedImage image = new BufferedImage(width, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setColor(withAlpha(color, FILL_ALPHA));
            g.fillRect(0, 0, width, 1);
            g.dispose();
            return image;
        }

        private BufferedImage renderEdgeImage(PriceZone zone, int width, boolean showLabel) {
            BufferedImage image = new BufferedImage(width, EDGE_IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color color = zone.getColor();
            int lineY = EDGE_IMAGE_HEIGHT / 2;
            g.setColor(withAlpha(color, EDGE_ALPHA));
            g.setStroke(new BasicStroke(2.0f));
            g.drawLine(0, lineY, width, lineY);

            if (showLabel && zone.getLabel() != null) {
                drawLabel(g, zone, color, lineY);
            }

            g.dispose();
            return image;
        }

        private void drawLabel(Graphics2D g, PriceZone zone, Color color, int lineY) {
            String text = zone.getLabel();
            g.setFont(LABEL_FONT);
            FontMetrics fm = g.getFontMetrics();
            int textWidth = fm.stringWidth(text);
            int textHeight = fm.getHeight();
            int padding = 4;
            int labelX = 10;
            int labelY = Math.max(0, lineY - textHeight / 2 - 1);

            g.setColor(withAlpha(color, LABEL_BACKGROUND_ALPHA));
            g.fillRect(labelX - padding, labelY, textWidth + padding * 2, textHeight + 2);
            g.setColor(Color.WHITE);
            g.drawString(text, labelX, lineY + fm.getAscent() / 2 - 1);
        }

        @Override
        public void dispose() {
            synchronized (shapeLock) {
                if (disposed) {
                    return;
                }
                disposed = true;
                store.removeListener(this);
                removeActiveShapesLocked();
                canvas.dispose();
            }
            painterToInstrument.remove(painterAlias);
            PluginLog.info("[PriceZonePainter] Disposed painter: " + painterAlias);
        }

        private void removeActiveShapesLocked() {
            List<CanvasIcon> icons = new ArrayList<>(activeShapes);
            activeShapes.clear();
            for (CanvasIcon icon : icons) {
                try {
                    canvas.removeShape(icon);
                } catch (IllegalArgumentException ignored) {
                    // Bookmap can deliver stale callbacks during teardown.
                }
            }
        }
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }
}
