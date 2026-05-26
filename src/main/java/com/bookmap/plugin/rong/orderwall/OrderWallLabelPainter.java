package com.bookmap.plugin.rong.orderwall;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.bookmap.plugin.rong.IndicatorConfig;
import com.bookmap.plugin.rong.PluginLog;

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
 * Draws compact order wall size labels directly over visible wall segments.
 */
public class OrderWallLabelPainter implements ScreenSpacePainterFactory, IndicatorConfig.ChangeListener {

    public static final String PAINTER_NAME_PREFIX = "wallLabels_";

    private static final Font LABEL_FONT = new Font("SansSerif", Font.BOLD, 11);
    private static final int LABEL_HEIGHT = 18;
    private static final int LABEL_PADDING_X = 6;
    private static final int MAX_VISIBLE_ACTIVE_LABELS = 5;
    private static final int MAX_RENDERED_SIZE_PATH_POINTS = 4;
    private static final String SIZE_PATH_SEPARATOR = String.valueOf((char) 0x2192);

    private static final Color ASK_ACCENT = new Color(235, 107, 82);
    private static final Color BID_ACCENT = new Color(91, 188, 255);
    private static final Color ACTIVE_BACKGROUND = new Color(18, 26, 34, 220);
    private static final Color INACTIVE_BACKGROUND = new Color(18, 26, 34, 130);
    private static final Color ACTIVE_TEXT = new Color(255, 255, 255);
    private static final Color INACTIVE_TEXT = new Color(216, 223, 228, 190);

    private final OrderWallLabelStore store;
    private final IndicatorConfig config;
    private final Map<String, String> painterToInstrument = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<PainterInstance>> paintersByInstrument = new ConcurrentHashMap<>();
    private volatile String lastRegisteredInstrument;

    public OrderWallLabelPainter(OrderWallLabelStore store, IndicatorConfig config) {
        this.store = store;
        this.config = config;
        this.config.addChangeListener(this);
    }

    public void registerInstrument(String instrumentAlias) {
        lastRegisteredInstrument = instrumentAlias;
        painterToInstrument.put(PAINTER_NAME_PREFIX + instrumentAlias, instrumentAlias);
    }

    public void unregisterInstrument(String instrumentAlias) {
        painterToInstrument.entrySet().removeIf(entry -> instrumentAlias.equals(entry.getValue()));
        paintersByInstrument.remove(instrumentAlias);
    }

    public void refreshInstrument(String instrumentAlias) {
        List<PainterInstance> painters = paintersByInstrument.get(instrumentAlias);
        if (painters == null) {
            return;
        }
        for (PainterInstance painter : painters) {
            painter.rebuildLabels();
        }
    }

    @Override
    public void onIndicatorConfigChanged(String indicatorKey, boolean enabled) {
        if (!IndicatorConfig.ORDER_WALL_SIZE_LABELS.equals(indicatorKey)) {
            return;
        }
        for (String instrumentAlias : paintersByInstrument.keySet()) {
            refreshInstrument(instrumentAlias);
        }
    }

    public void shutdown() {
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
            painterToInstrument.put(alias, instrumentAlias);
        }
        PainterInstance instance = new PainterInstance(alias, instrumentAlias, canvas);

        if (instrumentAlias != null) {
            paintersByInstrument
                    .computeIfAbsent(instrumentAlias, ignored -> new CopyOnWriteArrayList<>())
                    .add(instance);
        }
        PluginLog.info("[OrderWallLabelPainter] Created painter: " + alias + " -> " + instrumentAlias);
        return instance;
    }

    private class PainterInstance implements ScreenSpacePainterAdapter {

        private final String painterAlias;
        private final String instrumentAlias;
        private final ScreenSpaceCanvas canvas;
        private final Map<String, CanvasIcon> activeShapes = new HashMap<>();
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
            rebuildLabels();
        }

        @Override
        public void onHeatmapPriceHeight(long priceHeight) {
            this.priceHeight = priceHeight;
            rebuildLabels();
        }

        @Override
        public void onHeatmapTimeLeft(long timeLeft) {
            this.timeLeft = timeLeft;
            rebuildLabels();
        }

        @Override
        public void onHeatmapFullTimeWidth(long width) {
            this.timeWidth = width;
            rebuildLabels();
        }

        private void rebuildLabels() {
            synchronized (shapeLock) {
                if (instrumentAlias == null || disposed) {
                    return;
                }

                removeActiveShapesLocked();
                if (!config.isEnabled(IndicatorConfig.ORDER_WALL_SIZE_LABELS)) {
                    return;
                }

                List<LabelPlacement> labels = selectLabelsToDraw();

                for (LabelPlacement placement : labels) {
                    OrderWallLabel label = placement.label;
                    if (label.isActive()) {
                        store.markDisplayed(instrumentAlias, label.getId());
                    }
                    CanvasIcon icon = createLabelIcon(new LabelPlacement(label, placement.historicalSlot));
                    if (icon != null) {
                        canvas.addShape(icon);
                        activeShapes.put(label.getKey(), icon);
                    }
                }
            }
        }

        private boolean isVisible(OrderWallLabel label) {
            if (priceHeight <= 0) {
                return isVisibleInTime(label);
            }
            long top = priceBottom + priceHeight;
            return label.getPriceTick() >= priceBottom - 1
                    && label.getPriceTick() <= top + 1
                    && isVisibleInTime(label);
        }

        private List<LabelPlacement> selectLabelsToDraw() {
            List<OrderWallLabel> activeLabels = new ArrayList<>();
            List<OrderWallLabel> clearedLabels = new ArrayList<>();

            for (OrderWallLabel label : store.getLabels(instrumentAlias)) {
                if (!isVisible(label) || !isDisplayable(label)) {
                    continue;
                }
                if (label.isActive()) {
                    activeLabels.add(label);
                } else if (store.hasBeenDisplayed(instrumentAlias, label.getId())) {
                    clearedLabels.add(label);
                }
            }

            Comparator<OrderWallLabel> priority = Comparator
                    .comparingInt(OrderWallLabel::getPeakSize).reversed()
                    .thenComparing(Comparator.comparingLong(OrderWallLabel::getEndTimeNs).reversed());

            activeLabels.sort(priority);
            clearedLabels.sort(priority);

            List<LabelPlacement> selected = new ArrayList<>();
            for (OrderWallLabel label : limit(activeLabels, MAX_VISIBLE_ACTIVE_LABELS)) {
                selected.add(new LabelPlacement(label, 0));
            }
            for (OrderWallLabel label : clearedLabels) {
                selected.add(new LabelPlacement(label, 0));
            }
            selected.sort(Comparator
                    .comparingLong((LabelPlacement placement) -> anchorTimeNs(placement.label)).reversed()
                    .thenComparing(Comparator.comparingInt((LabelPlacement placement) -> placement.label.getPeakSize()).reversed()));
            return selected;
        }

        private boolean isDisplayable(OrderWallLabel label) {
            return label.getPeakSize() >= OrderWallLabel.DISPLAY_SIZE_UNIT;
        }

        private List<OrderWallLabel> limit(List<OrderWallLabel> labels, int maxCount) {
            if (labels.size() <= maxCount) {
                return labels;
            }
            return new ArrayList<>(labels.subList(0, maxCount));
        }

        private CanvasIcon createLabelIcon(LabelPlacement placement) {
            OrderWallLabel label = placement.label;
            BufferedImage image = renderLabelImage(label);
            PreparedImage prepared = new PreparedImage(image);
            long anchorTime = anchorTimeNs(label);
            CompositeHorizontalCoordinate anchor = new CompositeHorizontalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, 0, anchorTime);
            ScreenSpaceCanvas.HorizontalCoordinate x1 =
                    new RelativePixelHorizontalCoordinate(anchor, -image.getWidth() / 2);
            ScreenSpaceCanvas.HorizontalCoordinate x2 =
                    new RelativePixelHorizontalCoordinate(anchor, image.getWidth() - image.getWidth() / 2);

            CompositeVerticalCoordinate y1 = new CompositeVerticalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, -LABEL_HEIGHT / 2, label.getPriceTick());
            CompositeVerticalCoordinate y2 = new CompositeVerticalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, LABEL_HEIGHT / 2, label.getPriceTick());

            return new CanvasIcon(prepared, x1, y1, x2, y2);
        }

        private boolean isVisibleInTime(OrderWallLabel label) {
            if (timeWidth <= 0) {
                return true;
            }
            long timeRight = timeLeft + timeWidth;
            return label.getEndTimeNs() >= timeLeft && label.getStartTimeNs() <= timeRight;
        }

        private long anchorTimeNs(OrderWallLabel label) {
            if (timeWidth <= 0) {
                return midpoint(label.getStartTimeNs(), label.getEndTimeNs());
            }
            long visibleStart = Math.max(label.getStartTimeNs(), timeLeft);
            long visibleEnd = Math.min(label.getEndTimeNs(), timeLeft + timeWidth);
            if (visibleEnd < visibleStart) {
                return visibleStart;
            }
            return midpoint(visibleStart, visibleEnd);
        }

        private long midpoint(long start, long end) {
            return start + (end - start) / 2;
        }

        private BufferedImage renderLabelImage(OrderWallLabel label) {
            String text = formatSizePath(label);

            BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D probeGraphics = probe.createGraphics();
            probeGraphics.setFont(LABEL_FONT);
            FontMetrics metrics = probeGraphics.getFontMetrics();
            int width = metrics.stringWidth(text) + LABEL_PADDING_X * 2;
            probeGraphics.dispose();

            BufferedImage image = new BufferedImage(width, LABEL_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color accent = label.isBid() ? BID_ACCENT : ASK_ACCENT;
            Color background = label.isActive() ? ACTIVE_BACKGROUND : INACTIVE_BACKGROUND;
            Color textColor = label.isActive() ? ACTIVE_TEXT : INACTIVE_TEXT;

            g.setColor(background);
            g.fillRoundRect(0, 0, width - 1, LABEL_HEIGHT - 1, 8, 8);

            g.setColor(accent);
            g.setStroke(new BasicStroke(1.6f));
            g.drawRoundRect(0, 0, width - 1, LABEL_HEIGHT - 1, 8, 8);

            g.setFont(LABEL_FONT);
            g.setColor(textColor);
            FontMetrics fm = g.getFontMetrics();
            int textY = (LABEL_HEIGHT - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(text, LABEL_PADDING_X, textY);
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
            PluginLog.info("[OrderWallLabelPainter] Disposed painter: " + painterAlias);
        }

        private void removeActiveShapesLocked() {
            List<CanvasIcon> icons = new ArrayList<>(activeShapes.values());
            activeShapes.clear();
            for (CanvasIcon icon : icons) {
                try {
                    canvas.removeShape(icon);
                } catch (IllegalArgumentException ignored) {
                    // Bookmap can still deliver stale callbacks during teardown; ignore already-removed shapes.
                }
            }
        }

        private class LabelPlacement {
            private final OrderWallLabel label;
            private final int historicalSlot;

            private LabelPlacement(OrderWallLabel label, int historicalSlot) {
                this.label = label;
                this.historicalSlot = historicalSlot;
            }
        }
    }

    private static String formatSizePath(OrderWallLabel label) {
        List<Integer> sizePath = label.getSizePath();
        if (sizePath.isEmpty()) {
            return Integer.toString(OrderWallLabel.toDisplaySize(label.getPeakSize()));
        }
        List<Integer> renderedPath = compactSizePath(sizePath);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < renderedPath.size(); i++) {
            if (i > 0) {
                builder.append(SIZE_PATH_SEPARATOR);
            }
            builder.append(renderedPath.get(i));
        }
        return builder.toString();
    }

    private static List<Integer> compactSizePath(List<Integer> sizePath) {
        if (sizePath.size() <= MAX_RENDERED_SIZE_PATH_POINTS) {
            return sizePath;
        }
        List<Integer> compactPath = new ArrayList<>();
        appendIfDifferent(compactPath, sizePath.get(0));
        int start = sizePath.size() - (MAX_RENDERED_SIZE_PATH_POINTS - 1);
        for (int i = start; i < sizePath.size(); i++) {
            appendIfDifferent(compactPath, sizePath.get(i));
        }
        return compactPath;
    }

    private static void appendIfDifferent(List<Integer> values, int value) {
        if (values.isEmpty() || values.get(values.size() - 1) != value) {
            values.add(value);
        }
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
                return tail;
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
