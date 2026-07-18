package com.bookmap.plugin.rong.patterns;

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

/** Draws display-only scored pattern badges directly on the Bookmap heatmap. */
public class PatternSignalPainter implements ScreenSpacePainterFactory,
        PatternSignalStore.ChangeListener, IndicatorConfig.ChangeListener {

    public static final String PAINTER_NAME_PREFIX = "bookmapPatternSignals_";

    private static final Font HEADLINE_FONT = new Font("SansSerif", Font.BOLD, 14);
    private static final Font REASON_FONT = new Font("SansSerif", Font.PLAIN, 11);
    private static final Color LONG_COLOR = new Color(48, 214, 137);
    private static final Color SHORT_COLOR = new Color(255, 91, 91);
    private static final Color TEXT_COLOR = new Color(255, 255, 255);
    private static final int PADDING_X = 9;
    private static final int PADDING_Y = 5;
    private static final int LINE_GAP = 2;
    private static final int MAX_VISIBLE = 10;

    private final PatternSignalStore store;
    private final IndicatorConfig config;
    private final Map<String, String> painterToInstrument = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<PainterInstance>> paintersByInstrument =
            new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private volatile String lastRegisteredInstrument;

    public PatternSignalPainter(PatternSignalStore store, IndicatorConfig config) {
        this.store = store;
        this.config = config;
        store.addListener(this);
        config.addChangeListener(this);
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pattern-signal-painter");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(this::refreshVisibleSignals, 500, 500, TimeUnit.MILLISECONDS);
    }

    public void registerInstrument(String instrumentAlias) {
        lastRegisteredInstrument = instrumentAlias;
        painterToInstrument.put(PAINTER_NAME_PREFIX + instrumentAlias, instrumentAlias);
    }

    public void unregisterInstrument(String instrumentAlias) {
        painterToInstrument.entrySet().removeIf(entry -> instrumentAlias.equals(entry.getValue()));
        paintersByInstrument.remove(instrumentAlias);
    }

    @Override
    public void onPatternSignalsChanged(String instrumentAlias) {
        refreshInstrument(instrumentAlias);
    }

    @Override
    public void onIndicatorConfigChanged(String indicatorKey, boolean enabled) {
        if (!IndicatorConfig.BOOKMAP_PATTERN_SIGNALS.equals(indicatorKey)) {
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

    private void refreshVisibleSignals() {
        for (String instrumentAlias : paintersByInstrument.keySet()) {
            refreshInstrument(instrumentAlias);
        }
    }

    private void refreshInstrument(String instrumentAlias) {
        List<PainterInstance> painters = paintersByInstrument.get(instrumentAlias);
        if (painters == null) return;
        for (PainterInstance painter : painters) {
            painter.rebuild();
        }
    }

    @Override
    public ScreenSpacePainter createScreenSpacePainter(
            String alias, String fullName, ScreenSpaceCanvasFactory canvasFactory) {
        ScreenSpaceCanvas canvas = canvasFactory.createCanvas(ScreenSpaceCanvasType.HEATMAP);
        String instrumentAlias = extractInstrument(alias, fullName);
        if (instrumentAlias == null) {
            instrumentAlias = painterToInstrument.getOrDefault(alias, lastRegisteredInstrument);
        }
        if (instrumentAlias != null) {
            painterToInstrument.put(alias, instrumentAlias);
        }
        PainterInstance instance = new PainterInstance(alias, instrumentAlias, canvas);
        if (instrumentAlias != null) {
            paintersByInstrument.computeIfAbsent(
                    instrumentAlias, ignored -> new CopyOnWriteArrayList<>()).add(instance);
        }
        PluginLog.info("[PatternSignalPainter] Created painter: " + alias + " -> " + instrumentAlias);
        return instance;
    }

    private class PainterInstance implements ScreenSpacePainterAdapter {
        private final String painterAlias;
        private final String instrumentAlias;
        private final ScreenSpaceCanvas canvas;
        private final Map<String, CanvasIcon> activeShapes = new HashMap<>();
        private boolean disposed;

        PainterInstance(String painterAlias, String instrumentAlias, ScreenSpaceCanvas canvas) {
            this.painterAlias = painterAlias;
            this.instrumentAlias = instrumentAlias;
            this.canvas = canvas;
        }

        @Override
        public void onHeatmapFullPixelsWidth(int width) {
            rebuild();
        }

        private synchronized void rebuild() {
            if (disposed || instrumentAlias == null) return;
            removeShapes();
            if (!config.isEnabled(IndicatorConfig.BOOKMAP_PATTERN_SIGNALS)) return;
            List<BookmapPatternSignal> signals = store.getRecentSignals(
                    instrumentAlias, System.currentTimeMillis());
            int count = 0;
            for (BookmapPatternSignal signal : signals) {
                CanvasIcon icon = createBadge(signal);
                if (icon != null) {
                    canvas.addShape(icon);
                    activeShapes.put(signal.getId(), icon);
                    count++;
                }
                if (count >= MAX_VISIBLE) break;
            }
        }

        private CanvasIcon createBadge(BookmapPatternSignal signal) {
            if (signal.getEventTimeNs() <= 0) return null;
            BufferedImage image = renderBadge(signal);
            PreparedImage prepared = new PreparedImage(image);
            CompositeHorizontalCoordinate anchor = new CompositeHorizontalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, 0, signal.getEventTimeNs());
            ScreenSpaceCanvas.HorizontalCoordinate x1 =
                    new RelativePixelHorizontalCoordinate(anchor, -image.getWidth() / 2);
            ScreenSpaceCanvas.HorizontalCoordinate x2 =
                    new RelativePixelHorizontalCoordinate(anchor, image.getWidth() - image.getWidth() / 2);
            int offset = signal.getDirection() == Direction.LONG ? 8 : -8 - image.getHeight();
            CompositeVerticalCoordinate y1 = new CompositeVerticalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, offset, signal.getTriggerPriceTick());
            CompositeVerticalCoordinate y2 = new CompositeVerticalCoordinate(
                    CompositeCoordinateBase.DATA_ZERO, offset + image.getHeight(), signal.getTriggerPriceTick());
            return new CanvasIcon(prepared, x1, y1, x2, y2);
        }

        @Override
        public synchronized void dispose() {
            if (disposed) return;
            disposed = true;
            removeShapes();
            canvas.dispose();
            painterToInstrument.remove(painterAlias);
            List<PainterInstance> painters = paintersByInstrument.get(instrumentAlias);
            if (painters != null) painters.remove(this);
        }

        private void removeShapes() {
            List<CanvasIcon> icons = new ArrayList<>(activeShapes.values());
            activeShapes.clear();
            for (CanvasIcon icon : icons) {
                try {
                    canvas.removeShape(icon);
                } catch (IllegalArgumentException ignored) {
                    // Bookmap can deliver stale teardown callbacks.
                }
            }
        }
    }

    static BufferedImage renderBadge(BookmapPatternSignal signal) {
        String headline = headline(signal);
        String reason = reasonLine(signal);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(HEADLINE_FONT);
        FontMetrics headlineMetrics = pg.getFontMetrics();
        pg.setFont(REASON_FONT);
        FontMetrics reasonMetrics = pg.getFontMetrics();
        int width = Math.max(headlineMetrics.stringWidth(headline), reasonMetrics.stringWidth(reason))
                + PADDING_X * 2;
        int height = PADDING_Y * 2 + headlineMetrics.getHeight()
                + (reason.isEmpty() ? 0 : LINE_GAP + reasonMetrics.getHeight());
        pg.dispose();

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color accent = colorFor(signal);
        g.setColor(new Color(9, 13, 18, 238));
        g.fillRoundRect(1, 1, width - 2, height - 2, 12, 12);
        g.setColor(accent);
        g.setStroke(new BasicStroke(signal.getTier() == BookmapPatternSignal.QualityTier.VERY_HIGH ? 3f : 2f));
        g.drawRoundRect(1, 1, width - 3, height - 3, 12, 12);
        g.setColor(TEXT_COLOR);
        g.setFont(HEADLINE_FONT);
        FontMetrics hm = g.getFontMetrics();
        int y = PADDING_Y + hm.getAscent();
        g.drawString(headline, PADDING_X, y);
        if (!reason.isEmpty()) {
            g.setFont(REASON_FONT);
            FontMetrics rm = g.getFontMetrics();
            y += hm.getDescent() + LINE_GAP + rm.getAscent();
            g.setColor(new Color(220, 226, 235));
            g.drawString(reason, PADDING_X, y);
        }
        g.dispose();
        return image;
    }

    static String headline(BookmapPatternSignal signal) {
        return signal.getDirection().name() + " · "
                + signal.getPatternType().getDisplayName() + " · " + signal.getScore();
    }

    static String reasonLine(BookmapPatternSignal signal) {
        List<String> parts = new ArrayList<>();
        ScoreContribution positive = signal.strongestPositiveContribution();
        ScoreContribution negative = signal.strongestNegativeContribution();
        if (positive != null) parts.add("+ " + positive.getDetail());
        if (negative != null) parts.add("− " + negative.getDetail());
        return String.join(" · ", parts);
    }

    private static Color colorFor(BookmapPatternSignal signal) {
        Color base = signal.getDirection() == Direction.LONG ? LONG_COLOR : SHORT_COLOR;
        int alpha;
        switch (signal.getTier()) {
            case LOW: alpha = 135; break;
            case MEDIUM: alpha = 180; break;
            case HIGH: alpha = 225; break;
            case VERY_HIGH:
            default: alpha = 255; break;
        }
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
    }

    private static String extractInstrument(String alias, String fullName) {
        for (String candidate : new String[]{alias, fullName}) {
            if (candidate == null) continue;
            int index = candidate.indexOf(PAINTER_NAME_PREFIX);
            if (index < 0) continue;
            String tail = candidate.substring(index + PAINTER_NAME_PREFIX.length());
            int separator = indexOfAny(tail, '#', '/', ' ');
            if (separator >= 0) tail = tail.substring(0, separator);
            if (!tail.isEmpty()) return tail;
        }
        return null;
    }

    private static int indexOfAny(String value, char... chars) {
        int best = -1;
        for (char c : chars) {
            int index = value.indexOf(c);
            if (index >= 0 && (best < 0 || index < best)) best = index;
        }
        return best;
    }
}
