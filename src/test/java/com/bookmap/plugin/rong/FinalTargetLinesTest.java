package com.bookmap.plugin.rong;

import com.bookmap.plugin.rong.miniviteapp.core.controllers.NativeViews;
import com.bookmap.plugin.rong.pricelines.PriceLine;
import com.bookmap.plugin.rong.pricelines.PriceLineStore;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class FinalTargetLinesTest {
    @Test void plannedTargetsReachChartStoreWithoutPositionAndSurviveAccountRefresh() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90);
        PriceLineStore store = new PriceLineStore();
        KeyLevelManager manager = new KeyLevelManager(store);
        server.registerKeyLevelConfigListener(manager);
        JsonObject view = JsonParser.parseString("{\"type\":\"market_ready\",\"symbol\":\"U\",\"plan\":{"
                + "\"keyLevels\":{\"otherLevels\":[{\"price\":52.15,\"label\":\"ath\"}]},"
                + "\"short\":{\"enabled\":true,\"finalTargets\":[{\"level\":46.53,\"text\":\"46.53\"},{\"level\":45,\"text\":\"45\"}]},"
                + "\"long\":{\"enabled\":true,\"finalTargets\":[{\"level\":52.15,\"text\":\"ath\"},{\"level\":54,\"text\":\"54\"}]}}}").getAsJsonObject();
        NativeViews.project(view).forEach(server::acceptLocalMessage);
        // Configuration can arrive before instrument initialization.
        manager.onInstrumentInitialized("U:NASDAQ:STOCKS@BMD", .01);
        assertTargets(store);
        view.addProperty("type", "account_ready");
        view.add("account", JsonParser.parseString("{\"positions\":{\"U\":{\"netQuantity\":0}}}"));
        NativeViews.project(view).forEach(server::acceptLocalMessage);
        assertTargets(store);
        assertTrue(store.getLines("APP").isEmpty());
        manager.shutdown();
    }

    private static void assertTargets(PriceLineStore store) {
        List<PriceLine> lines = store.getLines("U");
        assertEquals(4, lines.size());
        assertEquals(List.of(45.0, 46.53, 52.15, 54.0), lines.stream().map(PriceLine::getRealPrice).sorted().collect(Collectors.toList()));
        for (PriceLine line : lines) {
            assertTrue(line.getCustomLabel().contains("target"));
            assertEquals(line.getRealPrice() / .01, line.getPriceInTicks(), .00001);
        }
    }
}
