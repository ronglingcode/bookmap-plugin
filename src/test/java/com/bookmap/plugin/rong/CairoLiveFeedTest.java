package com.bookmap.plugin.rong;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CairoLiveFeedTest {
    @Test void replayResetInvalidatesQueuedPricesAndIsDeliveredOnReconnect() {
        List<String> messages = new ArrayList<>();
        CairoLiveFeed feed = new CairoLiveFeed(messages::add);
        feed.chart("META", "replay", true);
        JsonObject old = feed.makeTrade("META", "replay", 103, 100);
        JsonObject reset = JsonParser.parseString("{\"type\":\"cairo_replay_reset\",\"symbol\":\"META\",\"epoch\":2}").getAsJsonObject();
        feed.replay(reset);
        feed.trade(old);
        assertEquals(2, messages.size());
        List<String> snapshot = new ArrayList<>();
        feed.snapshot(snapshot::add);
        assertEquals(2, snapshot.size());
        assertTrue(snapshot.get(1).contains("\"delivery\":\"snapshot\""));
        JsonObject fresh = feed.makeTrade("META", "replay", 99, 90);
        feed.trade(fresh);
        assertEquals(3, messages.size());
        assertEquals(2, fresh.get("tradeSequence").getAsInt());
    }

    @Test void reconciledAccountProjectionOnlyExportsSelectedFields() {
        List<String> messages = new ArrayList<>();
        CairoLiveFeed feed = new CairoLiveFeed(messages::add);
        JsonObject account = JsonParser.parseString("{\"symbol\":\"META\",\"position\":{\"netQuantity\":3,\"averagePrice\":101,\"privateField\":\"private\"},\"openOrders\":[{\"role\":\"STOP\",\"quantity\":3,\"price\":98}],\"executions\":[{\"orderID\":\"broker-internal\",\"isBuy\":true,\"positionEffectIsOpen\":true,\"quantity\":3,\"price\":101,\"timestamp\":20}]}").getAsJsonObject();
        feed.account(account, 21);
        feed.account(account, 21);
        assertEquals(1, messages.size());
        assertFalse(messages.get(0).contains("broker-internal"));
        assertFalse(messages.get(0).contains("privateField"));
        assertEquals(98, JsonParser.parseString(messages.get(0)).getAsJsonObject().getAsJsonArray("workingStops").get(0).getAsJsonObject().get("price").getAsInt());
    }
}
