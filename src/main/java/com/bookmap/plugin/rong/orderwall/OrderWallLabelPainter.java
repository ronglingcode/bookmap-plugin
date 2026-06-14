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
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
public class OrderWallLabelPainter implements ScreenSpacePainterFactory,
        IndicatorConfig.ChangeListener, OrderWallChangeStore.ChangeListener {

    public static final String PAINTER_NAME_PREFIX = "wallLabels_";

    private static final Font LABEL_FONT = new Font("SansSerif", Font.BOLD, 11);
    private static final int LABEL_HEIGHT = 18;
    private static final int LABEL_PADDING_X = 6;
    private static final Font CHANGE_LABEL_FONT = new Font("SansSerif", Font.BOLD, 14);
    private static final int CHANGE_LABEL_HEIGHT = 28;
    private static final int CHANGE_LABEL_PADDING_X = 8;
    private static final int CHANGE_ICON_SIZE = 20;
    private static final int CHANGE_ICON_GAP = 6;
    private static final long CHANGE_LABEL_TTL_MS = 30_000;
    private static final long CHANGE_LABEL_FLASH_MS = 8_000;
    private static final long CHANGE_EVENT_MATCH_TOLERANCE_NS = 2_000_000_000L;
    private static final int STACKED_LABEL_GAP = 3;
    private static final int MAX_VISIBLE_ACTIVE_LABELS = 5;
    private static final int MAX_RENDERED_SIZE_PATH_POINTS = 4;
    private static final String SIZE_PATH_SEPARATOR = " -> ";

    private static final Color ASK_ACCENT = new Color(235, 107, 82);
    private static final Color BID_ACCENT = new Color(91, 188, 255);
    private static final Color ACTIVE_BACKGROUND = new Color(18, 26, 34, 220);
    private static final Color INACTIVE_BACKGROUND = new Color(18, 26, 34, 130);
    private static final Color ACTIVE_TEXT = new Color(255, 255, 255);
    private static final Color INACTIVE_TEXT = new Color(216, 223, 228, 190);
    private static final Color ADDED_ACCENT = new Color(60, 220, 148);
    private static final Color REDUCED_ACCENT = new Color(255, 91, 78);
    private static final Color CHANGED_ACCENT = new Color(255, 196, 73);

    private final OrderWallLabelStore store;
    private final IndicatorConfig config;
    private final OrderWallChangeStore wallChangeStore;
    private final Map<String, String> painterToInstrument = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<PainterInstance>> paintersByInstrument = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private volatile String lastRegisteredInstrument;

    public OrderWallLabelPainter(OrderWallLabelStore store, IndicatorConfig config,
                                 OrderWallChangeStore wallChangeStore) {
        this.store = store;
        this.config = config;
        this.wallChangeStore = wallChangeStore;
        this.config.addChangeListener(this);
        this.wallChangeStore.addListener(this);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wall-label-painter");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::refreshAnimatedChangeLabels,
                250, 250, TimeUnit.MILLISECONDS);
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
        if (!IndicatorConfig.ORDER_WALL_SIZE_LABELS.equals(indicatorKey)
                && !IndicatorConfig.ORDER_WALL_CHANGE_ALERTS.equals(indicatorKey)) {
            return;
        }
        for (String instrumentAlias : paintersByInstrument.keySet()) {
            refreshInstrument(instrumentAlias);
        }
    }

    @Override
    public void onWallChangeEventsChanged(String instrumentAlias) {
        refreshInstrument(instrumentAlias);
    }

    public void shutdown() {
        config.removeChangeListener(this);
        wallChangeStore.removeListener(this);
        scheduler.shutdownNow();
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

    private void refreshAnimatedChangeLabels() {
        long nowMs = System.currentTimeMillis();
        if (!wallChangeStore.hasRecentEvents(CHANGE_LABEL_TTL_MS, nowMs)) {
            return;
        }
        for (String instrumentAlias : paintersByInstrument.keySet()) {
            refreshInstrument(instrumentAlias);
        }
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
                    if (label.isActive() || placement.changeEvent != null) {
                        store.markDisplayed(instrumentAlias, label.getId());
                    }
                    CanvasIcon icon = createLabelIcon(placement);
                    if (icon != null) {
                        canvas.addShape(icon);
                        activeShapes.put(placement.shapeKey(), icon);
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
            long nowMs = System.currentTimeMillis();

            for (OrderWallLabel label : store.getLabels(instrumentAlias)) {
                if (!isVisible(label) || !isDisplayable(label)) {
                    continue;
                }
                if (label.isActive()) {
                    activeLabels.add(label);
                } else if (store.hasBeenDisplayed(instrumentAlias, label.getId())
                        || findRecentChange(label, nowMs) != null) {
                    clearedLabels.add(label);
                }
            }

            Comparator<OrderWallLabel> priority = Comparator
                    .comparing((OrderWallLabel label) -> findRecentChange(label, nowMs) != null,
                            Comparator.reverseOrder())
                    .thenComparing(Comparator.comparingInt(OrderWallLabel::getPeakSize).reversed())
                    .thenComparing(Comparator.comparingLong(OrderWallLabel::getEndTimeNs).reversed());

            activeLabels.sort(priority);
            clearedLabels.sort(priority);

            List<OrderWallLabel> selected = new ArrayList<>();
            for (OrderWallLabel label : limit(activeLabels, MAX_VISIBLE_ACTIVE_LABELS)) {
                selected.add(label);
            }
            selected.addAll(clearedLabels);
            selected.sort(Comparator
                    .comparingLong((OrderWallLabel label) -> anchorTimeNs(label)).reversed()
                    .thenComparing(Comparator.comparingInt(OrderWallLabel::getPeakSize).reversed()));

            List<LabelPlacement> placements = new ArrayList<>();
            for (OrderWallLabel label : selected) {
                placements.add(new LabelPlacement(label, null, 0));
                OrderWallChangeEvent change = findRecentChange(label, nowMs);
                if (change != null) {
                    placements.add(new LabelPlacement(label, change, 0));
                }
            }
            return assignStackingSlots(placements);
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

        private List<LabelPlacement> assignStackingSlots(List<LabelPlacement> placements) {
            Map<String, Integer> nextSlotByPrice = new HashMap<>();
            List<LabelPlacement> stacked = new ArrayList<>(placements.size());
            for (LabelPlacement placement : placements) {
                String priceKey = placement.label.getPriceKey();
                int slot = nextSlotByPrice.getOrDefault(priceKey, 0);
                nextSlotByPrice.put(priceKey, slot + 1);
                stacked.add(new LabelPlacement(placement.label, placement.changeEvent, slot));
            }
            return stacked;
        }

        private CanvasIcon createLabelIcon(LabelPlacement placement) {
            OrderWallLabel label = placement.label;
            long nowMs = System.currentTimeMillis();
            OrderWallChangeEvent recentChange = placement.changeEvent;
            BufferedImage image = renderLabelImage(label, recentChange, nowMs);
            PreparedImage prepared = new PreparedImage(image);
            long anchorTime = anchorTimeNs(label, recentChange);
            CompositeHorizontalCoordinate anchor = new CompositeHorizontalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, 0, anchorTime);
            ScreenSpaceCanvas.HorizontalCoordinate x1 =
                    new RelativePixelHorizontalCoordinate(anchor, -image.getWidth() / 2);
            ScreenSpaceCanvas.HorizontalCoordinate x2 =
                    new RelativePixelHorizontalCoordinate(anchor, image.getWidth() - image.getWidth() / 2);

            int imageHeight = image.getHeight();
            int verticalOffset = placement.historicalSlot * (imageHeight + STACKED_LABEL_GAP);
            CompositeVerticalCoordinate y1 = new CompositeVerticalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, -imageHeight / 2 + verticalOffset, label.getPriceTick());
            CompositeVerticalCoordinate y2 = new CompositeVerticalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, imageHeight / 2 + verticalOffset, label.getPriceTick());

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

        private long anchorTimeNs(OrderWallLabel label, OrderWallChangeEvent recentChange) {
            if (recentChange == null) {
                return anchorTimeNs(label);
            }
            long eventTimeNs = recentChange.getEventTimeNs() > 0
                    ? recentChange.getEventTimeNs()
                    : label.getEndTimeNs();
            if (timeWidth <= 0) {
                return eventTimeNs;
            }
            long visibleStart = timeLeft;
            long visibleEnd = timeLeft + timeWidth;
            if (eventTimeNs < visibleStart) {
                return visibleStart;
            }
            if (eventTimeNs > visibleEnd) {
                return visibleEnd;
            }
            return eventTimeNs;
        }

        private long midpoint(long start, long end) {
            return start + (end - start) / 2;
        }

        private OrderWallChangeEvent findRecentChange(OrderWallLabel label, long nowMs) {
            if (!config.isEnabled(IndicatorConfig.ORDER_WALL_CHANGE_ALERTS)) {
                return null;
            }
            OrderWallChangeEvent bestEvent = null;
            long bestDistanceNs = Long.MAX_VALUE;
            for (OrderWallChangeEvent event : wallChangeStore.getRecentEvents(instrumentAlias, CHANGE_LABEL_TTL_MS, nowMs)) {
                if (event.isBid() != label.isBid() || event.getPriceTick() != label.getPriceTick()) {
                    continue;
                }
                if (!isEventTypeCompatible(label, event)) {
                    continue;
                }
                long eventTimeNs = event.getEventTimeNs();
                if (eventTimeNs <= 0) {
                    continue;
                }
                long start = label.getStartTimeNs() - CHANGE_EVENT_MATCH_TOLERANCE_NS;
                long end = label.getEndTimeNs() + CHANGE_EVENT_MATCH_TOLERANCE_NS;
                if (eventTimeNs < start || eventTimeNs > end) {
                    continue;
                }
                long anchor = event.getType() == OrderWallChangeEvent.Type.ADDED
                        ? label.getStartTimeNs()
                        : label.getEndTimeNs();
                long distanceNs = Math.abs(eventTimeNs - anchor);
                if (distanceNs < bestDistanceNs) {
                    bestEvent = event;
                    bestDistanceNs = distanceNs;
                }
            }
            return bestEvent;
        }

        private boolean isEventTypeCompatible(OrderWallLabel label, OrderWallChangeEvent event) {
            if (!label.isActive()) {
                return event.getType() != OrderWallChangeEvent.Type.ADDED;
            }
            return true;
        }

        private BufferedImage renderLabelImage(OrderWallLabel label, OrderWallChangeEvent recentChange, long nowMs) {
            if (recentChange != null) {
                return renderChangeLabelImage(label, recentChange, nowMs);
            }
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

        private BufferedImage renderChangeLabelImage(OrderWallLabel label, OrderWallChangeEvent event, long nowMs) {
            String text = formatChangeText(event);

            BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D probeGraphics = probe.createGraphics();
            probeGraphics.setFont(CHANGE_LABEL_FONT);
            FontMetrics metrics = probeGraphics.getFontMetrics();
            int textWidth = metrics.stringWidth(text);
            probeGraphics.dispose();

            int width = CHANGE_LABEL_PADDING_X * 2 + CHANGE_ICON_SIZE + CHANGE_ICON_GAP + textWidth;
            int height = CHANGE_LABEL_HEIGHT;
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color accent = changeAccent(event);
            int outlineAlpha = changePulseAlpha(event, nowMs);
            Color background = label.isActive()
                    ? new Color(10, 14, 18, 238)
                    : new Color(10, 14, 18, 205);

            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 42));
            g.fillRoundRect(0, 0, width - 1, height - 1, 12, 12);

            g.setColor(background);
            g.fillRoundRect(2, 2, width - 5, height - 5, 10, 10);

            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), outlineAlpha));
            g.setStroke(new BasicStroke(2.4f));
            g.drawRoundRect(2, 2, width - 5, height - 5, 10, 10);

            int iconX = CHANGE_LABEL_PADDING_X + CHANGE_ICON_SIZE / 2;
            int iconY = height / 2;
            drawChangeIcon(g, event.getType(), accent, iconX, iconY, outlineAlpha);

            g.setFont(CHANGE_LABEL_FONT);
            g.setColor(ACTIVE_TEXT);
            FontMetrics fm = g.getFontMetrics();
            int textX = CHANGE_LABEL_PADDING_X + CHANGE_ICON_SIZE + CHANGE_ICON_GAP;
            int textY = (height - fm.getHeight()) / 2 + fm.getAscent();
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
            private final OrderWallChangeEvent changeEvent;
            private final int historicalSlot;

            private LabelPlacement(OrderWallLabel label, OrderWallChangeEvent changeEvent, int historicalSlot) {
                this.label = label;
                this.changeEvent = changeEvent;
                this.historicalSlot = historicalSlot;
            }

            private String shapeKey() {
                String suffix = changeEvent == null ? "normal" : "change:" + changeEvent.getId();
                return label.getKey() + ":" + suffix + ":" + historicalSlot;
            }
        }
    }

    private static String formatChangeText(OrderWallChangeEvent event) {
        return OrderWallChangeEvent.formatSize(event.getPreviousSize())
                + " -> "
                + OrderWallChangeEvent.formatSize(event.getCurrentSize());
    }

    private static Color changeAccent(OrderWallChangeEvent event) {
        switch (event.getType()) {
            case ADDED:
                return ADDED_ACCENT;
            case REPLACED_SMALLER:
                return CHANGED_ACCENT;
            case REDUCED:
            default:
                return REDUCED_ACCENT;
        }
    }

    private static int changePulseAlpha(OrderWallChangeEvent event, long nowMs) {
        long age = Math.max(0, nowMs - event.getCreatedAtMs());
        if (age < CHANGE_LABEL_FLASH_MS) {
            double pulse = (Math.sin(nowMs / 85.0) + 1.0) / 2.0;
            return 150 + (int) (105 * pulse);
        }
        double remaining = 1.0 - ((double) (age - CHANGE_LABEL_FLASH_MS)
                / (CHANGE_LABEL_TTL_MS - CHANGE_LABEL_FLASH_MS));
        return Math.max(80, Math.min(180, (int) (80 + 100 * remaining)));
    }

    private static void drawChangeIcon(Graphics2D g, OrderWallChangeEvent.Type type, Color accent,
                                       int centerX, int centerY, int alpha) {
        int radius = CHANGE_ICON_SIZE / 2;
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), Math.min(255, alpha)));
        g.fillOval(centerX - radius, centerY - radius, CHANGE_ICON_SIZE, CHANGE_ICON_SIZE);
        g.setColor(new Color(255, 255, 255, 245));
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        switch (type) {
            case ADDED:
                g.drawLine(centerX - 5, centerY, centerX + 5, centerY);
                g.drawLine(centerX, centerY - 5, centerX, centerY + 5);
                break;
            case REPLACED_SMALLER:
                g.drawLine(centerX - 5, centerY - 3, centerX, centerY + 4);
                g.drawLine(centerX, centerY + 4, centerX + 5, centerY - 3);
                break;
            case REDUCED:
            default:
                g.drawLine(centerX - 5, centerY, centerX + 5, centerY);
                break;
        }
    }

    static String formatSizePath(OrderWallLabel label) {
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

        TreeSet<Integer> selectedIndexes = new TreeSet<>();
        selectedIndexes.add(0);
        selectedIndexes.add(indexOfPeak(sizePath));
        for (int i = sizePath.size() - 1;
             i >= 0 && selectedIndexes.size() < MAX_RENDERED_SIZE_PATH_POINTS;
             i--) {
            selectedIndexes.add(i);
        }

        List<Integer> compactPath = new ArrayList<>(selectedIndexes.size());
        for (int index : selectedIndexes) {
            appendIfDifferent(compactPath, sizePath.get(index));
        }
        return compactPath;
    }

    private static int indexOfPeak(List<Integer> sizePath) {
        int peakIndex = 0;
        int peakValue = sizePath.get(0);
        for (int i = 1; i < sizePath.size(); i++) {
            int value = sizePath.get(i);
            if (value > peakValue) {
                peakIndex = i;
                peakValue = value;
            }
        }
        return peakIndex;
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
