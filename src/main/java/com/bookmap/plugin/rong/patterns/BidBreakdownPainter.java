package com.bookmap.plugin.rong.patterns;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import velox.api.layer1.layers.strategies.interfaces.*;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.*;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory.ScreenSpaceCanvasType;

/** Shared next-bid and entry/reclaim rendering; subclasses supply only title and position. */
public class BidBreakdownPainter implements ScreenSpacePainterFactory {
    public static final String NAME_PREFIX = "bidBreakdown_";
    private static final Color WAITING = new Color(255, 196, 64), ENTRY = new Color(255, 100, 100), RECLAIM = new Color(100, 185, 255);
    private final BidBreakdownMonitor monitor;
    private final String title;
    private final int captionY;
    private final List<Instance> instances = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicBoolean dirty = new java.util.concurrent.atomic.AtomicBoolean(true);
    private final java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "bid-breakdown-painter"); thread.setDaemon(true); return thread;
    });
    public BidBreakdownPainter(BidBreakdownMonitor monitor) {
        this(monitor, "Bid breakdown", 150);
    }
    protected BidBreakdownPainter(BidBreakdownMonitor monitor, String title, int captionY) {
        this.monitor = monitor; this.title = title; this.captionY = captionY;
        scheduler.scheduleAtFixedRate(() -> { if (dirty.getAndSet(false)) refreshNow(); }, 100, 100, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    public final ScreenSpacePainter createScreenSpacePainter(String alias, String name, ScreenSpaceCanvasFactory factory) {
        Instance instance = new Instance(factory.createCanvas(ScreenSpaceCanvasType.HEATMAP));
        instances.add(instance); instance.refresh(); return instance;
    }
    public final void refresh() { dirty.set(true); }
    public final void refreshNow() { for (Instance instance : instances) instance.refresh(); }
    public final void shutdown() { scheduler.shutdownNow(); for (Instance instance : instances) instance.dispose(); }
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
            BidBreakdownMonitor.Snapshot state = monitor.snapshot();
            String next = state.enabled + ":" + state.regularSession + ":" + state.ready + ":" + state.liquid + ":" + state.nextBidTick + ":" + state.entries + ":" + state.waitingForOffer + ":" + state.offerTick;
            if (next.equals(signature)) return;
            signature = next; clear();
            if (!state.enabled) return;
            String status = !state.regularSession ? "waiting for market open" : !state.ready ? "waiting for snapshot"
                    : state.waitingForOffer ? "waiting for offer breakout" : !state.liquid ? "waiting for entry liquidity threshold" : "entries " + state.entries.size() + "/" + BidBreakdownMonitor.MAX_ENTRIES;
            BufferedImage caption = label(title + " · " + status, WAITING);
            add(new CanvasIcon(new PreparedImage(caption), pixelX(10), pixelY(captionY), pixelX(10 + caption.getWidth()), pixelY(captionY + 24)));
            Integer highlighted = state.waitingForOffer ? state.offerTick : state.nextBidTick;
            if (state.regularSession && highlighted != null) {
                BufferedImage wall = new BufferedImage(width, 24, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = wall.createGraphics(); g.setColor(WAITING); g.fillRect(0, 11, width, 2);
                BufferedImage badge = label(state.waitingForOffer ? "offer" : "next bid", WAITING);
                g.drawImage(badge, Math.max(0, width - badge.getWidth() - 10), 0, null); g.dispose();
                add(new CanvasIcon(new PreparedImage(wall), pixelX(0), dataY(highlighted, -12), pixelX(width), dataY(highlighted, 12)));
            }
            for (BidBreakdownMonitor.Entry entry : state.entries) {
                marker("entry " + entry.number, ENTRY, entry.bidTick, entry.entryTimeNs, true);
                if (entry.reclaimTimeNs != 0) marker("reclaim " + entry.number + " · stop out", RECLAIM, entry.bidTick, entry.reclaimTimeNs, false);
            }
        }
        private void marker(String text, Color color, int tick, long timeNs, boolean above) {
            BufferedImage badge = label(text, color);
            CompositeHorizontalCoordinate time = new CompositeHorizontalCoordinate(CompositeCoordinateBase.DATA_ZERO, 0, timeNs);
            add(new CanvasIcon(new PreparedImage(badge), new RelativePixelHorizontalCoordinate(time, -badge.getWidth() / 2),
                    dataY(tick, above ? -26 : 2), new RelativePixelHorizontalCoordinate(time, badge.getWidth() - badge.getWidth() / 2), dataY(tick, above ? -2 : 26)));
        }
        private void add(CanvasIcon shape) { canvas.addShape(shape); shapes.add(shape); }
        private void clear() { for (CanvasIcon shape : shapes) { try { canvas.removeShape(shape); } catch (IllegalArgumentException ignored) { } } shapes.clear(); }
        public synchronized void dispose() { if (disposed) return; disposed = true; clear(); canvas.dispose(); instances.remove(this); }
    }
    private static CompositeHorizontalCoordinate pixelX(int x) { return new CompositeHorizontalCoordinate(CompositeCoordinateBase.PIXEL_ZERO, x, 0); }
    private static CompositeVerticalCoordinate pixelY(int y) { return new CompositeVerticalCoordinate(CompositeCoordinateBase.PIXEL_ZERO, y, 0); }
    private static CompositeVerticalCoordinate dataY(int price, int offset) { return new CompositeVerticalCoordinate(CompositeCoordinateBase.DATA_ZERO, offset, price); }
    private static BufferedImage label(String text, Color color) {
        Font font = new Font("SansSerif", Font.BOLD, 12);
        Graphics2D probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics(); probe.setFont(font);
        int width = probe.getFontMetrics().stringWidth(text) + 16; probe.dispose();
        BufferedImage image = new BufferedImage(width, 24, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics(); g.setColor(new Color(9, 13, 18, 238)); g.fillRoundRect(0, 0, width, 24, 6, 6);
        g.setColor(color); g.drawRoundRect(0, 0, width - 1, 23, 6, 6); g.setFont(font); g.drawString(text, 8, 16); g.dispose(); return image;
    }
}
