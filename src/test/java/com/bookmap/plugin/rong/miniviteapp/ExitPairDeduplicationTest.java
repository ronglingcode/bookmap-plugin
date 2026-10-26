package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.AccountProjection;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ExitPairDeduplicationTest {
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static JsonObject closing(String id, String type) {
        JsonObject order = json("{\"orderStrategyType\":\"SINGLE\",\"status\":\"WORKING\",\"quantity\":8,\"price\":654,\"stopPrice\":648.25,\"orderLegCollection\":[{\"instruction\":\"SELL\",\"positionEffect\":\"CLOSING\",\"instrument\":{\"symbol\":\"META\",\"assetType\":\"EQUITY\"}}]}");
        order.addProperty("orderId", id); order.addProperty("orderType", type); return order;
    }
    private static JsonObject bracket(int index) {
        JsonObject oco = json("{\"orderStrategyType\":\"OCO\",\"status\":\"WORKING\"}");
        oco.addProperty("orderId", "oco-" + index);
        JsonArray legs = new JsonArray(); legs.add(closing("stop-" + index, "STOP")); legs.add(closing("limit-" + index, "LIMIT"));
        oco.add("childOrderStrategies", legs); return oco;
    }
    private static JsonArray project(JsonArray orders) {
        return AccountProjection.projectAccount(json("{\"currentBalances\":{\"liquidationValue\":25000}}"), orders,
                "2026-10-22", symbol -> 652).getAsJsonObject("exitPairs").getAsJsonArray("META");
    }
    @Test void eightBracketsReturnedUnderFilledEntryAndAsRootsCountOnce() {
        JsonArray children = new JsonArray(), orders = new JsonArray();
        for (int i = 0; i < 8; i++) { JsonObject oco = bracket(i); children.add(oco); orders.add(oco.deepCopy()); }
        JsonObject entry = json("{\"orderId\":\"entry\",\"orderStrategyType\":\"TRIGGER\",\"status\":\"FILLED\",\"cancelable\":false}");
        entry.add("childOrderStrategies", children); orders.add(entry);
        JsonArray pairs = project(orders);
        assertEquals(8, pairs.size(), "Separate brackets at identical prices must remain separate");
        Set<String> ids = new HashSet<>(); double stopQuantity = 0;
        for (JsonElement value : pairs) {
            JsonObject pair = value.getAsJsonObject();
            for (String role : new String[]{"STOP", "LIMIT"}) assertTrue(ids.add(pair.getAsJsonObject(role).get("orderID").getAsString()));
            stopQuantity += pair.getAsJsonObject("STOP").get("quantity").getAsDouble();
        }
        assertEquals(64, stopQuantity, "Duplicate brackets must not inflate stop coverage");
    }
    @Test void bracketOwnsLegsAlsoReturnedAsStandaloneOrdersRegardlessOfRootOrder() {
        for (boolean singlesFirst : new boolean[]{true, false}) {
            JsonArray orders = new JsonArray(); JsonObject oco = bracket(1);
            if (!singlesFirst) orders.add(oco);
            for (JsonElement leg : oco.getAsJsonArray("childOrderStrategies")) orders.add(leg.deepCopy());
            if (singlesFirst) orders.add(oco);
            orders.add(closing("independent-stop", "STOP"));
            JsonArray pairs = project(orders); assertEquals(2, pairs.size());
            assertEquals("oco-1", pairs.get(0).getAsJsonObject().get("parentOrderID").getAsString());
            assertTrue(pairs.get(0).getAsJsonObject().has("STOP")); assertTrue(pairs.get(0).getAsJsonObject().has("LIMIT"));
            assertEquals("independent-stop", pairs.get(1).getAsJsonObject().getAsJsonObject("STOP").get("orderID").getAsString());
        }
    }
    @Test void terminalStandaloneObservationOverridesStaleBracketInEitherRootOrder() {
        for (String status : new String[]{"REPLACED", "CANCELED", "FILLED", "REJECTED", "EXPIRED"})
            for (boolean terminalFirst : new boolean[]{true, false}) {
                JsonObject oco = bracket(1), terminal = closing("limit-1", "LIMIT");
                terminal.addProperty("status", status);
                JsonArray orders = new JsonArray();
                if (terminalFirst) orders.add(terminal);
                orders.add(oco);
                if (!terminalFirst) orders.add(terminal);
                String original = orders.toString();
                JsonArray pairs = project(orders);
                assertEquals(1, pairs.size());
                assertTrue(pairs.get(0).getAsJsonObject().has("STOP"));
                assertFalse(pairs.get(0).getAsJsonObject().has("LIMIT"), status);
                assertEquals(original, orders.toString(), "Projection must not mutate the broker diagnostics");
            }
    }
    @Test void filledAndCanceledCopiesCannotResurrectAnEntireStaleBracket() {
        JsonObject stale = bracket(1), current = stale.deepCopy();
        current.getAsJsonArray("childOrderStrategies").get(0).getAsJsonObject().addProperty("status", "CANCELED");
        current.getAsJsonArray("childOrderStrategies").get(1).getAsJsonObject().addProperty("status", "FILLED");
        JsonArray orders = new JsonArray(); orders.add(current); orders.add(stale);
        assertNull(project(orders), "No active pair should survive terminal observations of both IDs");
    }
    @Test void mostFilledLiveCopyControlsRemainingQuantity() {
        JsonObject oco = bracket(1), partial = closing("limit-1", "LIMIT");
        partial.addProperty("status", "PARTIALLY_FILLED"); partial.addProperty("filledQuantity", 5);
        JsonArray orders = new JsonArray(); orders.add(partial); orders.add(oco);
        JsonObject pair = project(orders).get(0).getAsJsonObject();
        assertEquals(3, pair.getAsJsonObject("LIMIT").get("quantity").getAsInt());
        assertEquals(3, pair.getAsJsonObject("STOP").get("quantity").getAsInt());
    }
}
