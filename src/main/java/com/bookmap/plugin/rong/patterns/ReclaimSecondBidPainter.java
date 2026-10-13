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

/** Live bid labels and persistent quote-confirmed breakdown/reclaim markers. */
public final class ReclaimSecondBidPainter implements ScreenSpacePainterFactory {
    private final ReclaimSecondBidMonitor monitor;
    private final String title = "Reclaim 2nd bid";
    private final int captionY = 120;

    private final List<Instance> instances = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicBoolean dirty = new java.util.concurrent.atomic.AtomicBoolean(true);
    private final java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "reclaim-second-bid-painter"); thread.setDaemon(true); return thread;
    });
    public static final String NAME_PREFIX = "reclaim2ndBid_";
    public ReclaimSecondBidPainter(ReclaimSecondBidMonitor monitor) {
        this.monitor = monitor;
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
            ReclaimSecondBidMonitor.Snapshot state = monitor.snapshot();
            String next = state.enabled + ":" + state.regularSession + ":" + state.nextBidTick + ":" + state.fills + ":" + state.signals;
            if (next.equals(signature)) return;
            signature = next; clear();
            if (!state.enabled) return;
            String status = !state.regularSession ? "waiting for market open" : "fills " + state.fills.size() + "/2";
            BufferedImage caption = label(title + " · " + status, new Color(255, 196, 64));
            add(new CanvasIcon(new PreparedImage(caption), pixelX(10), pixelY(captionY), pixelX(10 + caption.getWidth()), pixelY(captionY + caption.getHeight())));
            if (state.nextBidTick != null) level("next bid", new Color(255, 196, 64), state.nextBidTick);
            for (int i = 0; i < state.fills.size(); i++) level("fill " + (i + 1), new Color(100, 185, 255), state.fills.get(i).bidTick);
            for (ReclaimSecondBidMonitor.LongSignal signal : state.signals)
                marker("long", new Color(48, 214, 137), signal.priceTick, signal.timeNs, true);
        }
        private void level(String text, Color color, int tick) {
            BufferedImage wall = new BufferedImage(width, 24, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = wall.createGraphics(); g.setColor(color); g.fillRect(0, 11, width, 2);
            BufferedImage badge = label(text, color);
            g.drawImage(badge, Math.max(0, width - badge.getWidth() - 10), 0, null); g.dispose();
            add(new CanvasIcon(new PreparedImage(wall), pixelX(0), dataY(tick, -12), pixelX(width), dataY(tick, 12)));
        }
        private void marker(String text, Color color, int tick, long timeNs, boolean longSignal) {
            BufferedImage badge = entryMarker(text, color, longSignal);
            CompositeHorizontalCoordinate time = new CompositeHorizontalCoordinate(CompositeCoordinateBase.DATA_ZERO, 0, timeNs);
            int offset = longSignal ? 0 : -badge.getHeight();
            add(new CanvasIcon(new PreparedImage(badge), new RelativePixelHorizontalCoordinate(time, -badge.getWidth() / 2),
                    dataY(tick, offset), new RelativePixelHorizontalCoordinate(time, badge.getWidth() - badge.getWidth() / 2),
                    dataY(tick, offset + badge.getHeight())));
        }
        private void add(CanvasIcon shape) { canvas.addShape(shape); shapes.add(shape); }
        private void clear() { for (CanvasIcon shape : shapes) { try { canvas.removeShape(shape); } catch (IllegalArgumentException ignored) { } } shapes.clear(); }
        public synchronized void dispose() { if (disposed) return; disposed = true; clear(); canvas.dispose(); instances.remove(this); }
    }
    private static CompositeHorizontalCoordinate pixelX(int x) { return new CompositeHorizontalCoordinate(CompositeCoordinateBase.PIXEL_ZERO, x, 0); }
    private static CompositeVerticalCoordinate pixelY(int y) { return new CompositeVerticalCoordinate(CompositeCoordinateBase.PIXEL_ZERO, y, 0); }
    private static CompositeVerticalCoordinate dataY(int price, int offset) { return new CompositeVerticalCoordinate(CompositeCoordinateBase.DATA_ZERO, offset, price); }
    private static BufferedImage entryMarker(String text, Color color, boolean longSignal) {
        BufferedImage badge = label(text, color);
        BufferedImage marker = new BufferedImage(badge.getWidth(), badge.getHeight() + 8, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = marker.createGraphics(); g.drawImage(badge, 0, longSignal ? 8 : 0, null); g.setColor(color);
        int center = marker.getWidth() / 2;
        g.fillPolygon(new int[]{center - 5, center + 5, center}, longSignal
                ? new int[]{8, 8, 0} : new int[]{badge.getHeight(), badge.getHeight(), marker.getHeight() - 1}, 3);
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
