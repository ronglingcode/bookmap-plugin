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
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.RelativePixelHorizontalCoordinate;
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

    private static final long FLASH_TTL_MS = 8_000;
    private static final long MARKER_TTL_MS = 30_000;
    private static final int MAX_MARKERS = 8;
    private static final int MARKER_HEIGHT = 34;
    private static final int EVENT_BADGE_HEIGHT = 28;
    private static final int EVENT_BADGE_PADDING_X = 8;
    private static final int EVENT_BADGE_ICON_SIZE = 20;
    private static final int EVENT_BADGE_ICON_GAP = 6;
    private static final Font EVENT_BADGE_FONT = new Font("SansSerif", Font.BOLD, 14);

    private static final Color ADD_COLOR = new Color(60, 220, 148);
    private static final Color REDUCE_COLOR = new Color(255, 91, 78);
    private static final Color REPLACE_COLOR = new Color(255, 196, 73);
    private static final Color TEXT_COLOR = new Color(255, 255, 255);

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

                addMarkerShapes(events, nowMs);
                addEventBadgeShapes(events, nowMs);
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

        private void addEventBadgeShapes(List<OrderWallChangeEvent> events, long nowMs) {
            int count = 0;
            for (OrderWallChangeEvent event : events) {
                if (event.getType() != OrderWallChangeEvent.Type.ADDED
                        && event.getType() != OrderWallChangeEvent.Type.INCREASED) {
                    continue;
                }
                CanvasIcon icon = createEventBadgeIcon(event, nowMs);
                if (icon != null) {
                    canvas.addShape(icon);
                    activeShapes.put(event.getId() + ":eventBadge", icon);
                    count++;
                }
                if (count >= MAX_MARKERS) {
                    return;
                }
            }
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

        private CanvasIcon createEventBadgeIcon(OrderWallChangeEvent event, long nowMs) {
            if (event.getEventTimeNs() <= 0) {
                return null;
            }
            BufferedImage image = renderEventBadgeImage(event, nowMs);
            PreparedImage prepared = new PreparedImage(image);
            CompositeHorizontalCoordinate anchor = new CompositeHorizontalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, 0, event.getEventTimeNs());
            ScreenSpaceCanvas.HorizontalCoordinate x1 =
                    new RelativePixelHorizontalCoordinate(anchor, -image.getWidth() / 2);
            ScreenSpaceCanvas.HorizontalCoordinate x2 =
                    new RelativePixelHorizontalCoordinate(anchor, image.getWidth() - image.getWidth() / 2);

            CompositeVerticalCoordinate y1 = new CompositeVerticalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, EVENT_BADGE_HEIGHT / 2 + 4, event.getPriceTick());
            CompositeVerticalCoordinate y2 = new CompositeVerticalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, EVENT_BADGE_HEIGHT / 2 + 4 + image.getHeight(), event.getPriceTick());
            return new CanvasIcon(prepared, x1, y1, x2, y2);
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
            g.dispose();
            return image;
        }

        private BufferedImage renderEventBadgeImage(OrderWallChangeEvent event, long nowMs) {
            String text = OrderWallChangeEvent.formatSize(event.getPreviousSize())
                    + " -> "
                    + OrderWallChangeEvent.formatSize(event.getCurrentSize());
            BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D probeGraphics = probe.createGraphics();
            probeGraphics.setFont(EVENT_BADGE_FONT);
            FontMetrics metrics = probeGraphics.getFontMetrics();
            int textWidth = metrics.stringWidth(text);
            probeGraphics.dispose();

            int width = EVENT_BADGE_PADDING_X * 2 + EVENT_BADGE_ICON_SIZE + EVENT_BADGE_ICON_GAP + textWidth;
            BufferedImage image = new BufferedImage(width, EVENT_BADGE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color accent = colorFor(event);
            int outlineAlpha = markerAlpha(event, nowMs);
            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 46));
            g.fillRoundRect(0, 0, width - 1, EVENT_BADGE_HEIGHT - 1, 12, 12);
            g.setColor(new Color(10, 14, 18, 235));
            g.fillRoundRect(2, 2, width - 5, EVENT_BADGE_HEIGHT - 5, 10, 10);
            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), Math.min(255, outlineAlpha + 60)));
            g.setStroke(new BasicStroke(2.4f));
            g.drawRoundRect(2, 2, width - 5, EVENT_BADGE_HEIGHT - 5, 10, 10);

            int iconX = EVENT_BADGE_PADDING_X + EVENT_BADGE_ICON_SIZE / 2;
            int iconY = EVENT_BADGE_HEIGHT / 2;
            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), Math.min(255, outlineAlpha + 70)));
            g.fillOval(iconX - EVENT_BADGE_ICON_SIZE / 2, iconY - EVENT_BADGE_ICON_SIZE / 2,
                    EVENT_BADGE_ICON_SIZE, EVENT_BADGE_ICON_SIZE);
            g.setColor(new Color(255, 255, 255, 245));
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            drawEventBadgeIcon(g, event.getType(), iconX, iconY);

            g.setFont(EVENT_BADGE_FONT);
            g.setColor(TEXT_COLOR);
            FontMetrics fm = g.getFontMetrics();
            int textX = EVENT_BADGE_PADDING_X + EVENT_BADGE_ICON_SIZE + EVENT_BADGE_ICON_GAP;
            int textY = (EVENT_BADGE_HEIGHT - fm.getHeight()) / 2 + fm.getAscent();
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
            case INCREASED:
                return ADD_COLOR;
            case REPLACED_SMALLER:
                return REPLACE_COLOR;
            case REDUCED:
            default:
                return REDUCE_COLOR;
        }
    }

    private static void drawEventBadgeIcon(Graphics2D g, OrderWallChangeEvent.Type type,
                                           int centerX, int centerY) {
        switch (type) {
            case INCREASED:
                g.drawLine(centerX, centerY + 5, centerX, centerY - 5);
                g.drawLine(centerX, centerY - 5, centerX - 4, centerY - 1);
                g.drawLine(centerX, centerY - 5, centerX + 4, centerY - 1);
                break;
            case ADDED:
            default:
                g.drawLine(centerX - 5, centerY, centerX + 5, centerY);
                g.drawLine(centerX, centerY - 5, centerX, centerY + 5);
                break;
        }
    }

    private static int flashAlpha(OrderWallChangeEvent event, long nowMs, int high, int low) {
        long age = Math.max(0, nowMs - event.getCreatedAtMs());
        if (age > FLASH_TTL_MS) {
            return low;
        }
        double pulse = (Math.sin(nowMs / 90.0) + 1.0) / 2.0;
        int alpha = low + (int) ((high - low) * pulse);
        if (age > FLASH_TTL_MS - 2_000) {
            double fade = (FLASH_TTL_MS - age) / 2_000.0;
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
