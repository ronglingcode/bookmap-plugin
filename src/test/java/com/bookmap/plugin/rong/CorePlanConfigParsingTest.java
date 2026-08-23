package com.bookmap.plugin.rong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class CorePlanConfigParsingTest {

    @Test
    void activePlanIsDeliveredToMatchingSymbolListener() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90, 1000);
        AtomicReference<CorePlanConfigDefinition> configRef = new AtomicReference<>();
        server.registerCorePlanConfigListener("AAPL", configRef::set);

        server.onMessage(null, activeConfigJson(110, 5, 109, true));

        CorePlanConfigDefinition config = configRef.get();
        assertTrue(config.hasActiveTrade());
        assertTrue(config.isLongPosition());
        assertEquals(100, config.getEntryPrice(), 0.00001);
        assertEquals(110, config.getCoreTarget(), 0.00001);
        assertEquals(5, config.getCoreCount());
        assertEquals(109, config.getBufferedTarget(), 0.00001);
        assertEquals(3, config.getPartialsTaken());
        assertTrue(config.isReminderRequested());
    }

    @Test
    void inactivePlanClearsTheCachedActivePlan() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90, 1000);
        AtomicReference<CorePlanConfigDefinition> configRef = new AtomicReference<>();
        server.registerCorePlanConfigListener("AAPL", configRef::set);

        server.onMessage(null, activeConfigJson(110, 5, 109, false));
        server.onMessage(null, "{"
                + "\"type\":\"core_plan_config\","
                + "\"priceUnit\":\"real\","
                + "\"symbol\":\"AAPL\","
                + "\"hasActiveTrade\":false,"
                + "\"timestamp\":1785243961000"
                + "}");

        assertFalse(configRef.get().hasActiveTrade());
    }

    @Test
    void invalidCountIsRejected() {
        SignalWebSocketServer server = new SignalWebSocketServer(0, 90, 1000);
        AtomicReference<CorePlanConfigDefinition> configRef = new AtomicReference<>();
        server.registerCorePlanConfigListener("AAPL", configRef::set);

        server.onMessage(null, activeConfigJson(110, 8, 109, false));

        assertNull(configRef.get());
    }

    private static String activeConfigJson(
            double target,
            int count,
            double bufferedTarget,
            boolean reminder) {
        return "{"
                + "\"type\":\"core_plan_config\","
                + "\"priceUnit\":\"real\","
                + "\"symbol\":\"AAPL\","
                + "\"hasActiveTrade\":true,"
                + "\"isLong\":true,"
                + "\"entryPrice\":100,"
                + "\"coreTarget\":" + target + ","
                + "\"coreCount\":" + count + ","
                + "\"bufferedTarget\":" + bufferedTarget + ","
                + "\"partialsTaken\":3,"
                + "\"tradeId\":\"AAPL:long:1785243900000\","
                + "\"reminderRequested\":" + reminder + ","
                + "\"timestamp\":1785243960000"
                + "}";
    }
}
