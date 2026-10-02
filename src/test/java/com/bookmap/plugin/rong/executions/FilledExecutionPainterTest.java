package com.bookmap.plugin.rong.executions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.bookmap.plugin.rong.IndicatorConfig;

import velox.api.layer1.layers.strategies.interfaces.CanvasContextMenuProvider;
import velox.api.layer1.layers.strategies.interfaces.CanvasMouseListener;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CanvasIcon;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CanvasShape;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CompositeHorizontalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CompositeVerticalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.RelativePixelHorizontalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainter;

class FilledExecutionPainterTest {

    @Test
    void existingFillAppearsOnCanvasCreationAndSurvivesChartMovement() {
        long fillTimeNs = Instant.parse("2026-10-02T13:30:00Z").toEpochMilli() * 1_000_000L;
        assertEquals(fillTimeNs, LocalDateTime.of(2026, 10, 2, 6, 30)
                .atZone(ZoneId.of("America/Los_Angeles")).toInstant().toEpochMilli() * 1_000_000L);
        assertEquals(fillTimeNs, LocalDateTime.of(2026, 10, 2, 9, 30)
                .atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli() * 1_000_000L);

        FilledExecutionStore store = new FilledExecutionStore();
        store.replaceAll("NVDA", List.of(new FilledExecutionMarker(
                "NVDA", 23712, 237.12, 10, true, true, fillTimeNs)));
        FilledExecutionPainter painter = new FilledExecutionPainter(store, new IndicatorConfig());
        painter.registerInstrument("NVDA");
        RecordingCanvas canvas = new RecordingCanvas();
        ScreenSpacePainter instance = painter.createScreenSpacePainter(
                "filledExecutions_NVDA", "filledExecutions_NVDA", type -> canvas);

        assertEquals(1, canvas.shapes.size());
        assertFillAnchor(canvas.shapes.get(0), fillTimeNs);

        instance.onHeatmapFullPixelsWidth(1400);
        assertEquals(1, canvas.shapes.size());
        assertFillAnchor(canvas.shapes.get(0), fillTimeNs);

        instance.onMoveEnd();
        assertEquals(1, canvas.shapes.size());
        assertFillAnchor(canvas.shapes.get(0), fillTimeNs);
        instance.dispose();
        painter.shutdown();
    }

    private static void assertFillAnchor(CanvasShape shape, long fillTimeNs) {
        CanvasIcon icon = (CanvasIcon) shape;
        RelativePixelHorizontalCoordinate x1 = (RelativePixelHorizontalCoordinate) icon.getX1();
        CompositeHorizontalCoordinate anchor = (CompositeHorizontalCoordinate) x1.base;
        assertEquals(fillTimeNs, anchor.timeX);
        assertEquals(23712, ((CompositeVerticalCoordinate) icon.getY1()).dataY);
    }

    private static class RecordingCanvas implements ScreenSpaceCanvas {
        private final List<CanvasShape> shapes = new ArrayList<>();

        @Override public long getUniqueId() { return 1; }
        @Override public void dispose() { shapes.clear(); }
        @Override public void addShape(CanvasShape shape) { shapes.add(shape); }
        @Override public void removeShape(CanvasShape shape) { shapes.remove(shape); }
        @Override public void addMouseListener(CanvasMouseListener listener) { }
        @Override public void addContextMenuProvider(CanvasContextMenuProvider provider) { }
    }
}
