package com.bookmap.plugin.rong.signal;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import com.bookmap.plugin.rong.IndicatorConfig;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.*;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory.ScreenSpaceCanvasType;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterAdapter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterFactory;
import com.bookmap.plugin.rong.patterns.Direction;

/** Advisory badge formatting; acceptance uses the immutable first-validation record. */
public final class TradingSignalPainter implements ScreenSpacePainterFactory, IndicatorConfig.ChangeListener {
    public static final String PAINTER_NAME_PREFIX = "signalComposer_";
    private final TradingSignalStore store;
    private final IndicatorConfig config;
    private final Map<String, String> instruments = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Instance>> instances = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> refresh;
    private final Consumer<String> listener = alias -> {
        List<Instance> painters = instances.get(alias); if (painters != null) for (Instance painter : painters) painter.dirty = true;
    };
    public TradingSignalPainter(TradingSignalStore store, IndicatorConfig config) {
        this.store = store; this.config = config;
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "signal-composer-painter"); thread.setDaemon(true); return thread;
        });
        store.addListener(listener); config.addChangeListener(this); setRefreshEnabled(config.isEnabled(IndicatorConfig.SIGNAL_COMPOSER));
    }
    public void registerInstrument(String alias) { instruments.put(PAINTER_NAME_PREFIX + alias, alias); }
    public void unregisterInstrument(String alias) {
        instruments.entrySet().removeIf(entry -> alias.equals(entry.getValue()));
        List<Instance> painters = instances.remove(alias); if (painters != null) for (Instance painter : painters) painter.dispose();
    }
    public ScreenSpacePainter createScreenSpacePainter(String alias, String fullName, ScreenSpaceCanvasFactory factory) {
        String instrument = instruments.get(alias);
        if (instrument == null) for (Map.Entry<String, String> entry : instruments.entrySet()) {
            if (entry.getValue().equals(alias) || fullName != null && fullName.contains(entry.getKey())) { instrument = entry.getValue(); break; }
        }
        Instance painter = new Instance(instrument, factory.createCanvas(ScreenSpaceCanvasType.HEATMAP));
        if (instrument != null) instances.computeIfAbsent(instrument, key -> new CopyOnWriteArrayList<>()).add(painter);
        return painter;
    }
    public void onIndicatorConfigChanged(String key, boolean enabled) {
        if (!IndicatorConfig.SIGNAL_COMPOSER.equals(key)) return;
        setRefreshEnabled(enabled);
        try { scheduler.execute(this::refreshNow); } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }
    private synchronized void setRefreshEnabled(boolean enabled) {
        if (enabled && refresh == null && !scheduler.isShutdown()) refresh = scheduler.scheduleAtFixedRate(this::refreshNow, 500, 500, TimeUnit.MILLISECONDS);
        else if (!enabled && refresh != null) { refresh.cancel(false); refresh = null; }
    }
    /** Also expires receipt-time markers; never invokes the composer or advances market time. */
    public void refreshNow() { for (List<Instance> painters : instances.values()) for (Instance painter : painters) painter.rebuild(); }
    public void shutdown() {
        store.removeListener(listener); config.removeChangeListener(this); setRefreshEnabled(false); scheduler.shutdownNow();
        for (String alias : new ArrayList<>(instances.keySet())) unregisterInstrument(alias); instruments.clear();
    }
    private final class Instance implements ScreenSpacePainterAdapter {
        final String alias; final ScreenSpaceCanvas canvas;
        final Map<String, CanvasIcon> shapes = new HashMap<>();
        final Map<String, Integer> revisions = new HashMap<>();
        volatile boolean dirty = true; boolean disposed;
        Instance(String alias, ScreenSpaceCanvas canvas) { this.alias = alias; this.canvas = canvas; }
        public void onHeatmapFullPixelsWidth(int width) { rebuild(); }
        synchronized void rebuild() {
            if (disposed || alias == null) return;
            List<TradingSignal> signals = config.isEnabled(IndicatorConfig.SIGNAL_COMPOSER) ? store.snapshot(alias).signals : List.of();
            Map<String, TradingSignal> visible = new HashMap<>(); for (TradingSignal signal : signals) visible.put(signal.id, signal);
            for (String id : new ArrayList<>(shapes.keySet())) {
                TradingSignal signal = visible.get(id);
                if (signal == null || revisions.get(id) != signal.revision) remove(id);
            }
            for (TradingSignal signal : signals) if (!shapes.containsKey(signal.id)) {
                BufferedImage image = renderBadge(signal);
                CompositeHorizontalCoordinate anchor = new CompositeHorizontalCoordinate(CompositeCoordinateBase.DATA_ZERO, 0, anchorTimeNs(signal));
                int offset = signal.direction == Direction.LONG ? 8 : -8 - image.getHeight();
                CanvasIcon icon = new CanvasIcon(new PreparedImage(image),
                        new RelativePixelHorizontalCoordinate(anchor, -image.getWidth() / 2),
                        new CompositeVerticalCoordinate(CompositeCoordinateBase.DATA_ZERO, offset, signal.trigger.priceTick),
                        new RelativePixelHorizontalCoordinate(anchor, image.getWidth() - image.getWidth() / 2),
                        new CompositeVerticalCoordinate(CompositeCoordinateBase.DATA_ZERO, offset + image.getHeight(), signal.trigger.priceTick));
                canvas.addShape(icon); shapes.put(signal.id, icon); revisions.put(signal.id, signal.revision);
            }
            dirty = false;
        }
        private void remove(String id) {
            CanvasIcon icon = shapes.remove(id); revisions.remove(id);
            try { canvas.removeShape(icon); } catch (IllegalArgumentException ignored) { }
        }
        public synchronized void dispose() {
            if (disposed) return; disposed = true;
            for (String id : new ArrayList<>(shapes.keySet())) remove(id); canvas.dispose();
            List<Instance> painters = alias == null ? null : instances.get(alias); if (painters != null) painters.remove(this);
        }
    }
    public static List<String> badgeLines(TradingSignal signal) {
        TradingSignal.FirstValidation first = signal.firstValidation;
        String headline = signal.direction + " · " + signal.trigger.type.name().replace('_', ' ')
                + " " + quantity(signal.trigger.size);
        String thresholds = first.confirmationStrength + " · normal " + quantity(first.normalTriggerThreshold)
                + " / applied " + quantity(first.appliedTriggerThreshold);
        String reason = first.strongestConfirmation == null ? "Bid trigger meets normal requirement"
                : first.strongestConfirmation.event.type.name().replace('_', ' ') + " "
                + quantity(first.strongestConfirmation.event.size) + " · "
                + first.strongestConfirmation.ordering + " · " + first.strongestConfirmation.priceDistanceTicks + " ticks";
        if (signal.revision > 1) reason += " · updated evidence " + signal.latestConfirmationStrength;
        return List.of(headline, thresholds, reason);
    }
    public static long anchorTimeNs(TradingSignal signal) { return signal.firstValidation.eventTimeNs; }
    static String quantity(long size) {
        return size < 1000 ? Long.toString(size) : java.math.BigDecimal.valueOf(size).scaleByPowerOfTen(-3).stripTrailingZeros().toPlainString() + "K";
    }
    public static BufferedImage renderBadge(TradingSignal signal) {
        return renderLines(badgeLines(signal), signal.direction == Direction.LONG ? new Color(48, 214, 137) : new Color(255, 91, 91));
    }
    static BufferedImage renderLines(List<String> lines, Color accent) {
        Font headline = new Font("SansSerif", Font.BOLD, 13), detail = new Font("SansSerif", Font.PLAIN, 11);
        Graphics2D probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        int width = 0, height = 10;
        for (int i = 0; i < lines.size(); i++) {
            probe.setFont(i == 0 ? headline : detail); width = Math.max(width, probe.getFontMetrics().stringWidth(lines.get(i)));
            height += probe.getFontMetrics().getHeight() + 2;
        }
        probe.dispose(); width += 18;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB); Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(9, 13, 18, 238)); g.fillRoundRect(0, 0, width, height, 10, 10);
        g.setColor(accent); g.drawRoundRect(1, 1, width - 3, height - 3, 10, 10);
        int y = 5;
        for (int i = 0; i < lines.size(); i++) {
            g.setFont(i == 0 ? headline : detail); g.setColor(Color.WHITE);
            y += g.getFontMetrics().getAscent(); g.drawString(lines.get(i), 9, y);
            y += g.getFontMetrics().getDescent() + g.getFontMetrics().getLeading() + 2;
        }
        g.dispose(); return image;
    }
}
