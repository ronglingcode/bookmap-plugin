package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.account.TradeLedger;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.ExecutionInputs;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.ExtendedHandler;
import com.bookmap.plugin.rong.miniviteapp.core.state.TradeState;
import com.bookmap.plugin.rong.miniviteapp.models.Models.Snapshot;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PartialReloadDirectionTest {
    @Test void truncatedCoversDoNotTurnAmdShortReloadIntoLongWhileAnotherStockIsLong() {
        JsonObject executions = new JsonObject();
        JsonArray amd = new JsonArray();
        // The early short entries fell out of the live history, leaving covers.
        amd.add(fill(true, false, 48, 460, 1));
        amd.add(fill(false, true, 25, 447.56, 2));
        amd.add(fill(true, false, 25, 446.90, 3));
        executions.add("AMD", amd);
        JsonArray ant = new JsonArray();
        ant.add(fill(true, true, 100, 86.77, 4));
        executions.add("ANT", ant);
        JsonObject ledger = TradeLedger.projectTradeLedger(executions, 4000);
        JsonArray trades = ledger.getAsJsonObject("trades").getAsJsonArray("AMD");
        assertEquals(1, trades.size());
        // Reproduces the misleading legacy grouping; reload must use broker fills.
        assertTrue(trades.get(0).getAsJsonObject().get("isLong").getAsBoolean());
        boolean reloadLong = inputs("AMD", executions, ledger, 0).getAsJsonObject("entryContext").get("reloadIsLong").getAsBoolean();
        assertFalse(reloadLong);
        JsonArray fixtures = JsonParser.parseReader(new InputStreamReader(getClass().getResourceAsStream(
                "/extended-execution-fixtures.json"), StandardCharsets.UTF_8)).getAsJsonArray();
        JsonObject reload = null;
        for (JsonElement item : fixtures) if (item.getAsJsonObject().get("name").getAsString().equals("reload flat last trade uses one risk target"))
            reload = item.getAsJsonObject().getAsJsonObject("state").deepCopy();
        assertNotNull(reload);
        reload.addProperty("symbol", "AMD");
        JsonObject context = reload.getAsJsonObject("entryContext");
        context.addProperty("reloadIsLong", reloadLong);
        context.addProperty("selectedStopRequired", true);
        context.addProperty("customStopShort", 10.5);
        JsonObject order = ExtendedHandler.reload(new Snapshot(reload), true, 0).requests.get(0).body;
        JsonObject leg = order.getAsJsonArray("orderLegCollection").get(0).getAsJsonObject();
        assertEquals("SELL_SHORT", leg.get("instruction").getAsString());
        assertEquals("AMD", leg.getAsJsonObject("instrument").get("symbol").getAsString());
        assertTrue(inputs("ANT", executions, ledger, 0).getAsJsonObject("entryContext").get("reloadIsLong").getAsBoolean());
        // A current position takes precedence over a previous trade's direction.
        assertTrue(inputs("AMD", executions, ledger, 5).getAsJsonObject("entryContext").get("reloadIsLong").getAsBoolean());
    }

    @Test void orphanExitsIdentifyThePreviousDirectionForEitherSide() {
        for (boolean isLong : new boolean[]{false, true}) {
            JsonArray fills = new JsonArray();
            fills.add(fill(!isLong, false, 5, 100, 3));
            // Deliberately out of order; the newer closing fill sets direction.
            fills.add(fill(!isLong, true, 5, 99, 1));
            JsonObject executions = new JsonObject(); executions.add("AMD", fills);
            JsonObject ledger = TradeLedger.projectTradeLedger(executions, 4000);
            assertEquals(isLong, inputs("AMD", executions, ledger, 0)
                    .getAsJsonObject("entryContext").get("reloadIsLong").getAsBoolean());
            assertFalse(inputs("AMD", executions, ledger, -5)
                    .getAsJsonObject("entryContext").get("reloadIsLong").getAsBoolean());
        }
    }

    private static JsonObject fill(boolean buy, boolean opening, double quantity, double price, long time) {
        JsonObject fill = new JsonObject();
        fill.addProperty("isBuy", buy); fill.addProperty("positionEffectIsOpen", opening);
        fill.addProperty("quantity", quantity); fill.addProperty("price", price); fill.addProperty("timestamp", time);
        return fill;
    }

    private static JsonObject inputs(String symbol, JsonObject executions, JsonObject ledger, double net) {
        JsonArray fixtures = JsonParser.parseReader(new InputStreamReader(
                PartialReloadDirectionTest.class.getResourceAsStream("/state-fixtures.json"), StandardCharsets.UTF_8)).getAsJsonArray();
        for (JsonElement item : fixtures) {
            JsonObject fixture = item.getAsJsonObject();
            if (!fixture.get("kind").getAsString().equals("inputs")) continue;
            JsonArray args = fixture.getAsJsonArray("args").deepCopy();
            JsonObject account = args.get(4).getAsJsonObject();
            account.add("executions", executions.deepCopy());
            JsonObject positions = new JsonObject(), position = new JsonObject();
            position.addProperty("netQuantity", net); positions.add(symbol, position); account.add("positions", positions);
            TradeState state = new TradeState("2026-11-20", 25000, args.get(10).getAsLong() - 1000, null);
            return ExecutionInputs.create(symbol, args.get(1).getAsJsonObject(), args.get(2).getAsJsonObject(),
                    args.get(3).getAsJsonObject(), account, ledger, state, args.get(7).getAsJsonArray(),
                    args.get(8).getAsJsonObject(), args.get(9).getAsJsonObject(), args.get(10).getAsLong(),
                    args.get(11).getAsLong(), args.get(12).getAsJsonObject());
        }
        throw new AssertionError("Missing execution-input fixture");
    }
}
