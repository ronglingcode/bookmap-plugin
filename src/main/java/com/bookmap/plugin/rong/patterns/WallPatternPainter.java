package com.bookmap.plugin.rong.patterns;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.*;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory.ScreenSpaceCanvasType;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterAdapter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterFactory;

/** Shared rendering for independent pattern monitors. */
public abstract class WallPatternPainter implements ScreenSpacePainterFactory {
    private final WallPatternMonitor monitor;
    private final String title;
    private final int maxEntries, captionY;
    private final boolean markerAtBid;
    private final List<Instance> instances = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicBoolean dirty = new java.util.concurrent.atomic.AtomicBoolean(true);
    private final java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "false-breakout-painter"); thread.setDaemon(true); return thread;
    });
    protected WallPatternPainter(WallPatternMonitor monitor, String title, int maxEntries, int captionY, boolean markerAtBid) {
        this.monitor = monitor; this.title = title; this.maxEntries = maxEntries; this.captionY = captionY; this.markerAtBid = markerAtBid;
        scheduler.scheduleAtFixedRate(() -> { if (dirty.getAndSet(false)) refreshNow(); }, 100, 100, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    public ScreenSpacePainter createScreenSpacePainter(String alias, String name, ScreenSpaceCanvasFactory factory) {
        Instance instance = new Instance(factory.createCanvas(ScreenSpaceCanvasType.HEATMAP));
        instances.add(instance); instance.refresh(); return instance;
    }
    public void refresh() { dirty.set(true); }
    public void refreshNow() { for (Instance instance : instances) instance.refresh(); }
    public void shutdown() { scheduler.shutdownNow(); for (Instance instance : instances) instance.dispose(); }

    private final class Instance implements ScreenSpacePainterAdapter {
        private final ScreenSpaceCanvas canvas;
        private final List<CanvasIcon> shapes = new ArrayList<>();
        private String signature = "";
        private int width = 1;
        private boolean disposed;
        Instance(ScreenSpaceCanvas canvas) { this.canvas = canvas; }
        public synchronized void onHeatmapFullPixelsWidth(int width) { this.width = Math.max(1, width); signature = ""; refresh(); }
        synchronized void refresh() {
            if (disposed) return;
            WallPatternMonitor.Snapshot state = monitor.snapshot();
            String next = state.enabled + ":" + state.regularSession + ":" + state.phase + ":" + state.highlightedTick
                    + ":" + state.highlightedSize + ":" + state.entries;
            if (next.equals(signature)) return;
            signature = next; clear();
            if (!state.enabled) return;
            String status = !state.regularSession ? "waiting for market open" : state.phase == WallPatternMonitor.Phase.COMPLETE
                    ? "complete (" + maxEntries + "/" + maxEntries + ")"
                    : state.phase == WallPatternMonitor.Phase.BREAKOUT ? "1: offer breakout"
                    : (markerAtBid ? "bid breakdown (" : "2: bid breakdown (") + state.entries.size() + "/" + maxEntries + ")";
            BufferedImage caption = label(title + " · " + status, new Color(255, 196, 64));
            add(new CanvasIcon(new PreparedImage(caption), pixelX(10), pixelY(captionY), pixelX(10 + caption.getWidth()), pixelY(captionY + caption.getHeight())));
            if (state.highlightedTick != null) {
                Color color = state.phase == WallPatternMonitor.Phase.BREAKOUT ? new Color(255, 196, 64) : new Color(255, 100, 100);
                BufferedImage wall = new BufferedImage(width, 24, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = wall.createGraphics(); g.setColor(color); g.fillRect(0, 11, width, 2);
                BufferedImage text = label((state.phase == WallPatternMonitor.Phase.BREAKOUT ? "offer " : "bid ") + (state.highlightedSize / 1000) + "k", color);
                g.drawImage(text, Math.max(0, width - text.getWidth() - 10), 0, null); g.dispose();
                add(new CanvasIcon(new PreparedImage(wall), pixelX(0), dataY(state.highlightedTick, -12), pixelX(width), dataY(state.highlightedTick, 12)));
            }
            for (WallPatternMonitor.Entry entry : state.entries) {
                BufferedImage badge = entryMarker(entry.number);
                int markerTick = markerAtBid ? entry.bidTick : entry.priceTick;
                CompositeHorizontalCoordinate time = new CompositeHorizontalCoordinate(CompositeCoordinateBase.DATA_ZERO, 0, entry.timeNs);
                add(new CanvasIcon(new PreparedImage(badge), new RelativePixelHorizontalCoordinate(time, -badge.getWidth() / 2),
                        dataY(markerTick, -badge.getHeight()), new RelativePixelHorizontalCoordinate(time, badge.getWidth() - badge.getWidth() / 2), dataY(markerTick, 0)));
            }
        }
        private void add(CanvasIcon shape) { canvas.addShape(shape); shapes.add(shape); }
        private void clear() { for (CanvasIcon shape : shapes) { try { canvas.removeShape(shape); } catch (IllegalArgumentException ignored) { } } shapes.clear(); }
        public synchronized void dispose() { if (disposed) return; disposed = true; clear(); canvas.dispose(); instances.remove(this); }
    }
    private static CompositeHorizontalCoordinate pixelX(int x) { return new CompositeHorizontalCoordinate(CompositeCoordinateBase.PIXEL_ZERO, x, 0); }
    private static CompositeVerticalCoordinate pixelY(int y) { return new CompositeVerticalCoordinate(CompositeCoordinateBase.PIXEL_ZERO, y, 0); }
    private static CompositeVerticalCoordinate dataY(int price, int offset) { return new CompositeVerticalCoordinate(CompositeCoordinateBase.DATA_ZERO, offset, price); }
    private static BufferedImage entryMarker(int number) {
        Color color = new Color(255, 100, 100);
        BufferedImage badge = label("bid " + number, color);
        BufferedImage marker = new BufferedImage(badge.getWidth(), badge.getHeight() + 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = marker.createGraphics(); g.drawImage(badge, 0, 0, null); g.setColor(color);
        int center = marker.getWidth() / 2;
        g.fillPolygon(new int[]{center - 5, center + 5, center},
                new int[]{badge.getHeight(), badge.getHeight(), marker.getHeight() - 1}, 3);
        g.dispose(); return marker;
    }
    private static BufferedImage label(String text, Color color) {
        Font font = new Font("SansSerif", Font.BOLD, 12);
        Graphics2D probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics(); probe.setFont(font);
        int width = probe.getFontMetrics().stringWidth(text) + 16; probe.dispose();
        BufferedImage image = new BufferedImage(width, 24, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics(); g.setColor(new Color(9, 13, 18, 238)); g.fillRoundRect(0, 0, width, 24, 6, 6);
        g.setColor(color); g.drawRoundRect(0, 0, width - 1, 23, 6, 6); g.setFont(font); g.drawString(text, 8, 16); g.dispose(); return image;
    }
}
