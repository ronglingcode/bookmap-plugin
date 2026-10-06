package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.*;
import static com.bookmap.plugin.rong.SignalComposerActivationTest.*;
import static com.bookmap.plugin.rong.SignalComposerCallbacksTest.*;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.JsonObject;
import com.bookmap.plugin.rong.patterns.*;
import com.bookmap.plugin.rong.pricelines.*;
import com.bookmap.plugin.rong.signal.SignalComposerConfig;

class SignalComposerIsolationTest {
    @TempDir Path directory;
    private List<String> run(boolean composition) throws Exception {
        List<String> legacy = new ArrayList<>(); Object previousServer = field("sharedServer").get(null);
        List<JsonObject> actions = new ArrayList<>(); List<String> broadcasts = new ArrayList<>();
        SignalWebSocketServer server = new SignalWebSocketServer(0, 97) { public void broadcast(String value) { broadcasts.add(value); } };
        server.setTradingDispatch(actions::add);
        try {
            field("sharedServer").set(null, server);
            withIsolatedActivation(() -> {
                Path config = directory.resolve("composer.json"); Files.writeString(config, "{\"enabled\":true}");
                System.setProperty(SignalComposerConfig.CONFIG_PROPERTY, config.toString());
                RongPlugin p = callbackPlugin(); if (!composition) p.onIndicatorConfigChanged(IndicatorConfig.SIGNAL_COMPOSER, false);
                BookmapPatternEngine engine = new BookmapPatternEngine("TEST", .01, () -> 5000, 97,
                        (OrderBookState)field("orderBook").get(p), new PriceLineStore(), new PriceZoneStore(), type -> true,
                        signal -> legacy.add(signal.getPatternType() + ":" + signal.getScore() + ":" + signal.getEventTimeNs() + ":" + signal.getTriggerPriceTick()));
                field("patternEngine").set(p, engine); field("patternAutomationEnabled").setBoolean(p, true);
                time(p, 0); p.onSnapshotEnd(); p.onBbo(5100, 1, 5121, 1); time(p, 100); p.onTrade(5090, 1, null);
                time(p, 2000); p.onDepth(true, 5100, 6000); time(p, 2500);
                time(p, 2600); p.onDepth(true, 5101, 6000); time(p, 3100); time(p, 3600);
                assertFalse(legacy.isEmpty()); assertTrue(actions.isEmpty()); assertTrue(broadcasts.isEmpty());
                if (composition) assertEquals(1, store().snapshot("TEST").signals.size()); else assertTrue(store().snapshot("TEST").signals.isEmpty());
                JsonObject manual = new JsonObject(); manual.addProperty("symbol", "TEST"); manual.addProperty("keyCode", "KeyC");
                server.dispatchTradingAction(manual); assertEquals(1, actions.size());
            });
        } finally { field("sharedServer").set(null, previousServer); server.shutdown(); }
        return legacy;
    }
    @Test void composerChangesNeitherLegacyOutputNorManualRoutingAndDispatchesNothing() throws Exception {
        assertEquals(run(false), run(true));
    }
}
