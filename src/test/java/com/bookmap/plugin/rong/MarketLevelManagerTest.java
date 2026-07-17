package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.bookmap.plugin.rong.pricelines.PriceLine;
import com.bookmap.plugin.rong.pricelines.PriceLineStore;

class MarketLevelManagerTest {

    @Test
    void disablingCamPivotsClearsResistanceAndSupportLinesWithOneRefresh() {
        PriceLineStore store = new PriceLineStore();
        IndicatorConfig config = new IndicatorConfig();
        MarketLevelManager manager = new MarketLevelManager(store, config);

        manager.onInstrumentInitialized("MU:NASDAQ:STOCKS@BMD", 0.01);
        manager.onMarketLevelsChanged("MU", new MarketLevelDefinition(
                "MU",
                camPivots(),
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN));

        List<PriceLine> drawnLines = store.getLines("MU");
        assertEquals(12, drawnLines.size());

        AtomicInteger refreshCount = new AtomicInteger();
        store.addListener(changedInstrument -> {
            if ("MU".equals(changedInstrument)) {
                refreshCount.incrementAndGet();
            }
        });

        config.setEnabled(IndicatorConfig.CAM_PIVOTS, false);

        assertEquals(1, refreshCount.get());
        assertFalse(store.getLines("MU").stream().anyMatch(line -> line.getType().name().startsWith("CAM_R")));
        assertFalse(store.getLines("MU").stream().anyMatch(line -> line.getType().name().startsWith("CAM_S")));
    }

    @Test
    void missingCamPivotsClearsPreviouslyDrawnPivotLines() {
        PriceLineStore store = new PriceLineStore();
        IndicatorConfig config = new IndicatorConfig();
        MarketLevelManager manager = new MarketLevelManager(store, config);

        manager.onInstrumentInitialized("MU:NASDAQ:STOCKS@BMD", 0.01);
        manager.onMarketLevelsChanged("MU", new MarketLevelDefinition(
                "MU",
                camPivots(),
                Double.NaN,
                Double.NaN,
                Double.NaN,
                Double.NaN));
        assertEquals(12, store.getLines("MU").size());

        manager.onMarketLevelsChanged("MU", new MarketLevelDefinition(
                "MU",
                Collections.emptyMap(),
                1088.00,
                1024.00,
                1080.00,
                1050.00));

        List<PriceLine> drawnLines = store.getLines("MU");
        assertEquals(4, drawnLines.size());
        assertFalse(drawnLines.stream().anyMatch(line -> line.getType().name().startsWith("CAM_R")));
        assertFalse(drawnLines.stream().anyMatch(line -> line.getType().name().startsWith("CAM_S")));
    }

    private Map<String, Double> camPivots() {
        Map<String, Double> pivots = new LinkedHashMap<>();
        pivots.put("R1", 1070.10);
        pivots.put("R2", 1074.20);
        pivots.put("R3", 1078.30);
        pivots.put("R4", 1082.40);
        pivots.put("R5", 1086.50);
        pivots.put("R6", 1090.60);
        pivots.put("S1", 1026.34);
        pivots.put("S2", 1020.40);
        pivots.put("S3", 1014.46);
        pivots.put("S4", 996.64);
        pivots.put("S5", 990.50);
        pivots.put("S6", 984.40);
        return pivots;
    }
}
