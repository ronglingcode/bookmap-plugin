package com.bookmap.plugin.rong.orderwall;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory.ScreenSpaceCanvasType;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterAdapter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterFactory;

/**
 * Draws visual-only wall change alerts on the heatmap.
 */
public class OrderWallChangePainter implements ScreenSpacePainterFactory,
        OrderWallChangeStore.ChangeListener, IndicatorConfig.ChangeListener {

    public static final String PAINTER_NAME_PREFIX = "wallChangeAlerts_";

    private static final long BANNER_TTL_MS = 8_000;
    private static final long MARKER_TTL_MS = 30_000;
    private static final int MAX_BANNERS = 3;
    private static final int MAX_MARKERS = 8;
    private static final int BANNER_HEIGHT = 32;
    private static final int BANNER_GAP = 6;
    private static final int MARKER_HEIGHT = 34;
    private static final Font BANNER_FONT = new Font("SansSerif", Font.BOLD, 13);
    private static final Font MARKER_FONT = new Font("SansSerif", Font.BOLD, 12);

    private static final Color ADD_COLOR = new Color(60, 220, 148);
    private static final Color REDUCE_COLOR = new Color(255, 91, 78);
    private static final Color REPLACE_COLOR = new Color(255, 196, 73);
    private static final Color TEXT_COLOR = new Color(255, 255, 255);
    private static final Color DARK_BACKGROUND = new Color(12, 16, 20);

    private final OrderWallChangeStore store;
    private final IndicatorConfig config;
    private final Map<String, String> painterToInstrument = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<PainterInstance>> paintersByInstrument = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private volatile String lastRegisteredInstrument;

    public OrderWallChangePainter(OrderWallChangeStore store, IndicatorConfig config) {
        this.store = store;
        this.config = config;
        this.store.addListener(this);
        this.config.addChangeListener(this);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wall-change-painter");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::refreshAnimatedAlerts,
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
            painter.rebuildAlerts();
        }
    }

    @Override
    public void onWallChangeEventsChanged(String instrumentAlias) {
        refreshInstrument(instrumentAlias);
    }

    @Override
    public void onIndicatorConfigChanged(String indicatorKey, boolean enabled) {
        if (!IndicatorConfig.ORDER_WALL_CHANGE_ALERTS.equals(indicatorKey)) {
            return;
        }
        for (String instrumentAlias : paintersByInstrument.keySet()) {
            refreshInstrument(instrumentAlias);
        }
    }

    public void shutdown() {
        store.removeListener(this);
        config.removeChangeListener(this);
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
        PluginLog.info("[OrderWallChangePainter] Created painter: " + alias + " -> " + instrumentAlias);
        return instance;
    }

    private void refreshAnimatedAlerts() {
        long nowMs = System.currentTimeMillis();
        if (!store.hasRecentEvents(MARKER_TTL_MS, nowMs)) {
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

        private volatile int fullPixelsWidth;
        private volatile int activePixelsWidth;
        private boolean disposed;

        PainterInstance(String painterAlias, String instrumentAlias, ScreenSpaceCanvas canvas) {
            this.painterAlias = painterAlias;
            this.instrumentAlias = instrumentAlias;
            this.canvas = canvas;
        }

        @Override
        public void onHeatmapFullPixelsWidth(int width) {
            this.fullPixelsWidth = width;
            rebuildAlerts();
        }

        @Override
        public void onHeatmapActivePixelsWidth(int width) {
            this.activePixelsWidth = width;
            rebuildAlerts();
        }

        private void rebuildAlerts() {
            synchronized (shapeLock) {
                if (disposed || instrumentAlias == null) {
                    return;
                }
                removeActiveShapesLocked();
                if (!config.isEnabled(IndicatorConfig.ORDER_WALL_CHANGE_ALERTS)) {
                    return;
                }

                long nowMs = System.currentTimeMillis();
                List<OrderWallChangeEvent> events =
                        store.getRecentEvents(instrumentAlias, MARKER_TTL_MS, nowMs);
                if (events.isEmpty()) {
                    return;
                }

                addBannerShapes(events, nowMs);
                addMarkerShapes(events, nowMs);
            }
        }

        private void addBannerShapes(List<OrderWallChangeEvent> events, long nowMs) {
            int y = 12;
            int count = 0;
            for (OrderWallChangeEvent event : events) {
                if (nowMs - event.getCreatedAtMs() > BANNER_TTL_MS) {
                    continue;
                }
                CanvasIcon icon = createBannerIcon(event, y, nowMs);
                if (icon != null) {
                    canvas.addShape(icon);
                    activeShapes.put(event.getId() + ":banner:" + count, icon);
                    y += BANNER_HEIGHT + BANNER_GAP;
                    count++;
                }
                if (count >= MAX_BANNERS) {
                    return;
                }
            }
        }

        private void addMarkerShapes(List<OrderWallChangeEvent> events, long nowMs) {
            int count = 0;
            for (OrderWallChangeEvent event : events) {
                CanvasIcon icon = createMarkerIcon(event, nowMs);
                if (icon != null) {
                    canvas.addShape(icon);
                    activeShapes.put(event.getId() + ":marker", icon);
                    count++;
                }
                if (count >= MAX_MARKERS) {
                    return;
                }
            }
        }

        private CanvasIcon createBannerIcon(OrderWallChangeEvent event, int y, long nowMs) {
            BufferedImage image = renderBannerImage(event, nowMs);
            PreparedImage prepared = new PreparedImage(image);
            int x = 12;
            CompositeHorizontalCoordinate x1 = new CompositeHorizontalCoordinate(
                    CompositeCoordinateBase.PIXEL_ZERO, x, 0);
            CompositeHorizontalCoordinate x2 = new CompositeHorizontalCoordinate(
                    CompositeCoordinateBase.PIXEL_ZERO, x + image.getWidth(), 0);
            CompositeVerticalCoordinate y1 = new CompositeVerticalCoordinate(
                    CompositeCoordinateBase.PIXEL_ZERO, y, 0);
            CompositeVerticalCoordinate y2 = new CompositeVerticalCoordinate(
                    CompositeCoordinateBase.PIXEL_ZERO, y + image.getHeight(), 0);
            return new CanvasIcon(prepared, x1, y1, x2, y2);
        }

        private CanvasIcon createMarkerIcon(OrderWallChangeEvent event, long nowMs) {
            int width = Math.max(fullPixelsWidth, 1_200);
            BufferedImage image = renderMarkerImage(event, width, nowMs);
            PreparedImage prepared = new PreparedImage(image);

            CompositeHorizontalCoordinate x1 = new CompositeHorizontalCoordinate(
                    CompositeCoordinateBase.PIXEL_ZERO, 0, 0);
            CompositeHorizontalCoordinate x2 = new CompositeHorizontalCoordinate(
                    CompositeCoordinateBase.PIXEL_ZERO, width, 0);
            CompositeVerticalCoordinate y1 = new CompositeVerticalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, -MARKER_HEIGHT / 2, event.getPriceTick());
            CompositeVerticalCoordinate y2 = new CompositeVerticalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, MARKER_HEIGHT / 2, event.getPriceTick());
            return new CanvasIcon(prepared, x1, y1, x2, y2);
        }

        private BufferedImage renderBannerImage(OrderWallChangeEvent event, long nowMs) {
            int maxWidth = activePixelsWidth > 0 ? Math.max(220, activePixelsWidth - 24) : 520;
            maxWidth = Math.min(maxWidth, 560);
            int width = maxWidth;
            BufferedImage image = new BufferedImage(width, BANNER_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color accent = colorFor(event);
            int accentAlpha = flashAlpha(event, nowMs, 225, 150);
            g.setColor(new Color(DARK_BACKGROUND.getRed(), DARK_BACKGROUND.getGreen(),
                    DARK_BACKGROUND.getBlue(), 210));
            g.fillRoundRect(0, 0, width - 1, BANNER_HEIGHT - 1, 7, 7);
            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), accentAlpha));
            g.setStroke(new BasicStroke(2.4f));
            g.drawRoundRect(1, 1, width - 3, BANNER_HEIGHT - 3, 7, 7);
            g.fillRoundRect(0, 0, 7, BANNER_HEIGHT, 7, 7);

            g.setFont(BANNER_FONT);
            FontMetrics fm = g.getFontMetrics();
            String text = clipText(g, event.getLogMessage(), width - 24);
            g.setColor(TEXT_COLOR);
            int textY = (BANNER_HEIGHT - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(text, 14, textY);
            g.dispose();
            return image;
        }

        private BufferedImage renderMarkerImage(OrderWallChangeEvent event, int width, long nowMs) {
            BufferedImage image = new BufferedImage(width, MARKER_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color accent = colorFor(event);
            int alpha = markerAlpha(event, nowMs);
            int y = MARKER_HEIGHT / 2;

            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), alpha));
            g.setStroke(new BasicStroke(3.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10.0f, new float[]{12.0f, 7.0f}, 0.0f));
            g.drawLine(0, y, width, y);

            g.setFont(MARKER_FONT);
            FontMetrics fm = g.getFontMetrics();
            String label = clipText(g, event.getShortMessage(), Math.min(width - 28, 360));
            int labelWidth = fm.stringWidth(label) + 14;
            int labelHeight = 20;
            int labelX = 12;
            int labelY = y - labelHeight / 2;

            g.setColor(new Color(8, 12, 16, Math.min(210, alpha + 40)));
            g.fillRoundRect(labelX, labelY, labelWidth, labelHeight, 7, 7);
            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), Math.min(255, alpha + 60)));
            g.drawRoundRect(labelX, labelY, labelWidth, labelHeight, 7, 7);
            g.setColor(new Color(255, 255, 255, Math.min(255, alpha + 80)));
            g.drawString(label, labelX + 7, labelY + (labelHeight - fm.getHeight()) / 2 + fm.getAscent());
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
            PluginLog.info("[OrderWallChangePainter] Disposed painter: " + painterAlias);
        }

        private void removeActiveShapesLocked() {
            List<CanvasIcon> icons = new ArrayList<>(activeShapes.values());
            activeShapes.clear();
            for (CanvasIcon icon : icons) {
                try {
                    canvas.removeShape(icon);
                } catch (IllegalArgumentException ignored) {
                    // Bookmap can still deliver stale callbacks during teardown.
                }
            }
        }
    }

    private static Color colorFor(OrderWallChangeEvent event) {
        switch (event.getType()) {
            case ADDED:
                return ADD_COLOR;
            case REPLACED_SMALLER:
                return REPLACE_COLOR;
            case REDUCED:
            default:
                return REDUCE_COLOR;
        }
    }

    private static int flashAlpha(OrderWallChangeEvent event, long nowMs, int high, int low) {
        long age = Math.max(0, nowMs - event.getCreatedAtMs());
        if (age > BANNER_TTL_MS) {
            return low;
        }
        double pulse = (Math.sin(nowMs / 90.0) + 1.0) / 2.0;
        int alpha = low + (int) ((high - low) * pulse);
        if (age > BANNER_TTL_MS - 2_000) {
            double fade = (BANNER_TTL_MS - age) / 2_000.0;
            alpha = Math.max(40, (int) (alpha * fade));
        }
        return Math.max(0, Math.min(255, alpha));
    }

    private static int markerAlpha(OrderWallChangeEvent event, long nowMs) {
        long age = Math.max(0, nowMs - event.getCreatedAtMs());
        if (age > MARKER_TTL_MS) {
            return 0;
        }
        if (age < 6_000) {
            return flashAlpha(event, nowMs, 235, 120);
        }
        double fade = 1.0 - ((double) (age - 6_000) / (MARKER_TTL_MS - 6_000));
        return Math.max(35, (int) (120 * fade));
    }

    private static String clipText(Graphics2D g, String text, int maxWidth) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(text) <= maxWidth) {
            return text;
        }
        String suffix = "...";
        int suffixWidth = fm.stringWidth(suffix);
        int end = text.length();
        while (end > 0 && fm.stringWidth(text.substring(0, end)) + suffixWidth > maxWidth) {
            end--;
        }
        return end <= 0 ? suffix : text.substring(0, end) + suffix;
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
