package com.bookmap.plugin.rong.executions;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.bookmap.plugin.rong.IndicatorConfig;
import com.bookmap.plugin.rong.PluginLog;
import com.bookmap.plugin.rong.SymbolUtils;

import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CanvasIcon;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CompositeCoordinateBase;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CompositeHorizontalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CompositeVerticalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.PreparedImage;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.RelativePixelHorizontalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory.ScreenSpaceCanvasType;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterAdapter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterFactory;

/**
 * Draws filled broker executions at their fill time and fill price.
 */
public class FilledExecutionPainter implements ScreenSpacePainterFactory,
        FilledExecutionStore.ChangeListener, IndicatorConfig.ChangeListener {

    public static final String PAINTER_NAME_PREFIX = "filledExecutions_";

    private static final int MARKER_HEIGHT = 22;
    private static final int MARKER_MIN_WIDTH = 58;
    private static final int MARKER_PADDING_X = 7;
    private static final int MAX_VISIBLE_MARKERS = 300;
    private static final Font MARKER_FONT = new Font("SansSerif", Font.BOLD, 11);
    private static final Color BUY_COLOR = new Color(46, 125, 50);
    private static final Color SELL_COLOR = new Color(139, 0, 0);
    private static final Color TEXT_COLOR = new Color(255, 255, 255);
    private static final Color BACKGROUND = new Color(10, 14, 18, 220);

    private final FilledExecutionStore store;
    private final IndicatorConfig config;
    private final Map<String, String> painterToInstrument = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<PainterInstance>> paintersByInstrument = new ConcurrentHashMap<>();
    private volatile String lastRegisteredInstrument;

    public FilledExecutionPainter(FilledExecutionStore store, IndicatorConfig config) {
        this.store = store;
        this.config = config;
        this.store.addListener(this);
        this.config.addChangeListener(this);
    }

    public void registerInstrument(String instrumentAlias) {
        String cleanInstrumentAlias = SymbolUtils.cleanSymbol(instrumentAlias);
        lastRegisteredInstrument = cleanInstrumentAlias;
        painterToInstrument.put(PAINTER_NAME_PREFIX + cleanInstrumentAlias, cleanInstrumentAlias);
    }

    public void unregisterInstrument(String instrumentAlias) {
        String cleanInstrumentAlias = SymbolUtils.cleanSymbol(instrumentAlias);
        painterToInstrument.entrySet().removeIf(entry -> cleanInstrumentAlias.equals(entry.getValue()));
        paintersByInstrument.remove(cleanInstrumentAlias);
    }

    public void refreshInstrument(String instrumentAlias) {
        List<PainterInstance> painters = paintersByInstrument.get(instrumentAlias);
        if (painters == null) {
            return;
        }
        for (PainterInstance painter : painters) {
            painter.rebuildMarkers();
        }
    }

    private void refreshAllInstruments() {
        for (CopyOnWriteArrayList<PainterInstance> painters : paintersByInstrument.values()) {
            for (PainterInstance painter : painters) {
                painter.rebuildMarkers();
            }
        }
    }

    @Override
    public void onFilledExecutionsChanged(String instrumentAlias) {
        refreshInstrument(instrumentAlias);
    }

    @Override
    public void onIndicatorConfigChanged(String indicatorKey, boolean enabled) {
        if (!IndicatorConfig.FILLED_EXECUTION_MARKERS.equals(indicatorKey)) {
            return;
        }
        refreshAllInstruments();
    }

    public void shutdown() {
        store.removeListener(this);
        config.removeChangeListener(this);
    }

    @Override
    public ScreenSpacePainter createScreenSpacePainter(String alias, String fullName,
                                                       ScreenSpaceCanvasFactory canvasFactory) {
        ScreenSpaceCanvas canvas = canvasFactory.createCanvas(ScreenSpaceCanvasType.HEATMAP);

        String instrumentAlias = extractInstrumentFromPainterName(alias, fullName);
        if (instrumentAlias == null) {
            instrumentAlias = painterToInstrument.getOrDefault(alias, lastRegisteredInstrument);
        }
        if (instrumentAlias != null) {
            instrumentAlias = SymbolUtils.cleanSymbol(instrumentAlias);
            painterToInstrument.put(alias, instrumentAlias);
        }

        PainterInstance instance = new PainterInstance(alias, instrumentAlias, canvas);
        if (instrumentAlias != null) {
            paintersByInstrument
                    .computeIfAbsent(instrumentAlias, ignored -> new CopyOnWriteArrayList<>())
                    .add(instance);
        }
        PluginLog.info("[FilledExecutionPainter] Created painter: " + alias + " -> " + instrumentAlias);
        return instance;
    }

    private class PainterInstance implements ScreenSpacePainterAdapter {

        private final String painterAlias;
        private final String instrumentAlias;
        private final ScreenSpaceCanvas canvas;
        private final List<CanvasIcon> activeShapes = new ArrayList<>();
        private final Object shapeLock = new Object();

        private volatile long priceBottom;
        private volatile long priceHeight;
        private volatile long timeLeft;
        private volatile long timeWidth;
        private boolean disposed;

        PainterInstance(String painterAlias, String instrumentAlias, ScreenSpaceCanvas canvas) {
            this.painterAlias = painterAlias;
            this.instrumentAlias = instrumentAlias;
            this.canvas = canvas;
        }

        @Override
        public void onHeatmapPriceBottom(long priceBottom) {
            this.priceBottom = priceBottom;
            rebuildMarkers();
        }

        @Override
        public void onHeatmapPriceHeight(long priceHeight) {
            this.priceHeight = priceHeight;
            rebuildMarkers();
        }

        @Override
        public void onHeatmapTimeLeft(long timeLeft) {
            this.timeLeft = timeLeft;
            rebuildMarkers();
        }

        @Override
        public void onHeatmapFullTimeWidth(long width) {
            this.timeWidth = width;
            rebuildMarkers();
        }

        private void rebuildMarkers() {
            synchronized (shapeLock) {
                if (disposed || instrumentAlias == null) {
                    return;
                }
                removeActiveShapesLocked();
                if (!config.isEnabled(IndicatorConfig.FILLED_EXECUTION_MARKERS)) {
                    return;
                }

                List<FilledExecutionMarker> markers = selectMarkersToDraw();
                for (FilledExecutionMarker marker : markers) {
                    CanvasIcon icon = createMarkerIcon(marker);
                    if (icon != null) {
                        canvas.addShape(icon);
                        activeShapes.add(icon);
                    }
                }
            }
        }

        private List<FilledExecutionMarker> selectMarkersToDraw() {
            List<FilledExecutionMarker> visible = new ArrayList<>();
            for (FilledExecutionMarker marker : store.getMarkers(instrumentAlias)) {
                if (isVisible(marker)) {
                    visible.add(marker);
                }
            }
            visible.sort(Comparator.comparingLong(FilledExecutionMarker::getTimeNs));
            if (visible.size() <= MAX_VISIBLE_MARKERS) {
                return visible;
            }
            return new ArrayList<>(visible.subList(visible.size() - MAX_VISIBLE_MARKERS, visible.size()));
        }

        private boolean isVisible(FilledExecutionMarker marker) {
            if (marker.getTimeNs() <= 0) {
                return false;
            }
            if (timeWidth > 0) {
                long timeRight = timeLeft + timeWidth;
                if (marker.getTimeNs() < timeLeft || marker.getTimeNs() > timeRight) {
                    return false;
                }
            }
            if (priceHeight <= 0) {
                return true;
            }
            double top = priceBottom + priceHeight;
            return marker.getPriceInTicks() >= priceBottom - 1
                    && marker.getPriceInTicks() <= top + 1;
        }

        private CanvasIcon createMarkerIcon(FilledExecutionMarker marker) {
            BufferedImage image = renderMarkerImage(marker);
            PreparedImage prepared = new PreparedImage(image);

            CompositeHorizontalCoordinate anchor = new CompositeHorizontalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, 0, marker.getTimeNs());
            ScreenSpaceCanvas.HorizontalCoordinate x1 =
                    new RelativePixelHorizontalCoordinate(anchor, -image.getWidth() / 2);
            ScreenSpaceCanvas.HorizontalCoordinate x2 =
                    new RelativePixelHorizontalCoordinate(anchor, image.getWidth() - image.getWidth() / 2);

            CompositeVerticalCoordinate y1 = new CompositeVerticalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, -image.getHeight() / 2, marker.getPriceInTicks());
            CompositeVerticalCoordinate y2 = new CompositeVerticalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, image.getHeight() / 2, marker.getPriceInTicks());

            return new CanvasIcon(prepared, x1, y1, x2, y2);
        }

        private BufferedImage renderMarkerImage(FilledExecutionMarker marker) {
            String text = (marker.isBuy() ? "B " : "S ")
                    + formatQuantity(marker.getQuantity())
                    + " @ "
                    + formatPrice(marker.getRealPrice());

            BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D probeGraphics = probe.createGraphics();
            probeGraphics.setFont(MARKER_FONT);
            FontMetrics metrics = probeGraphics.getFontMetrics();
            int textWidth = metrics.stringWidth(text);
            probeGraphics.dispose();

            int textX = MARKER_PADDING_X + 11;
            int width = Math.max(MARKER_MIN_WIDTH, textX + textWidth + MARKER_PADDING_X);
            BufferedImage image = new BufferedImage(width, MARKER_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color accent = marker.isBuy() ? BUY_COLOR : SELL_COLOR;
            g.setColor(BACKGROUND);
            g.fillRoundRect(0, 0, width - 1, MARKER_HEIGHT - 1, 10, 10);

            int alpha = marker.isOpening() ? 235 : 170;
            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), alpha));
            g.setStroke(new BasicStroke(marker.isOpening() ? 2.2f : 1.6f));
            g.drawRoundRect(1, 1, width - 3, MARKER_HEIGHT - 3, 9, 9);
            g.fillOval(5, MARKER_HEIGHT / 2 - 4, 8, 8);

            g.setFont(MARKER_FONT);
            g.setColor(TEXT_COLOR);
            FontMetrics fm = g.getFontMetrics();
            int textY = (MARKER_HEIGHT - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(text, textX, textY);
            g.dispose();
            return image;
        }

        @Override
        public void dispose() {
            synchronized (shapeLock) {
                if (disposed) {
                    return;
                }
                disposed = true;
                removeActiveShapesLocked();
                canvas.dispose();
            }
            painterToInstrument.remove(painterAlias);
            List<PainterInstance> painters = paintersByInstrument.get(instrumentAlias);
            if (painters != null) {
                painters.remove(this);
                if (painters.isEmpty()) {
                    paintersByInstrument.remove(instrumentAlias);
                }
            }
            PluginLog.info("[FilledExecutionPainter] Disposed painter: " + painterAlias);
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

    private static String formatQuantity(double quantity) {
        if (Math.rint(quantity) == quantity) {
            return Long.toString(Math.round(quantity));
        }
        return String.format(Locale.US, "%.2f", quantity);
    }

    private static String formatPrice(double price) {
        return String.format(Locale.US, "%.2f", price);
    }

    static String extractInstrumentFromPainterName(String painterAlias, String fullName) {
        String[] candidates = {painterAlias, fullName};
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            int index = candidate.indexOf(PAINTER_NAME_PREFIX);
            if (index < 0) {
                continue;
            }
            String tail = candidate.substring(index + PAINTER_NAME_PREFIX.length());
            int separator = indexOfAny(tail, '#', '/', ' ');
            if (separator >= 0) {
                tail = tail.substring(0, separator);
            }
            if (!tail.isEmpty()) {
                return SymbolUtils.cleanSymbol(tail);
            }
        }
        return null;
    }

    private static int indexOfAny(String value, char... chars) {
        int best = -1;
        for (char c : chars) {
            int index = value.indexOf(c);
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }
}
