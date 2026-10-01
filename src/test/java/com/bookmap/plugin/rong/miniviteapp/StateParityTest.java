package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.controllers.NativeViews;
import com.bookmap.plugin.rong.miniviteapp.core.account.TradeLedger;
import com.bookmap.plugin.rong.miniviteapp.core.configuration.TradingConfig;
import com.bookmap.plugin.rong.miniviteapp.core.state.TradeState;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.ExecutionInputs;
import com.bookmap.plugin.rong.miniviteapp.core.controllers.Workflows;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StateParityTest {
    @Test void agreesWithProductionTypeScript() {
        JsonArray fixtures = JsonParser.parseReader(new InputStreamReader(getClass().getResourceAsStream("/state-fixtures.json"), StandardCharsets.UTF_8)).getAsJsonArray();
        for (JsonElement value : fixtures) {
            JsonObject fixture = value.getAsJsonObject(); JsonArray args = fixture.getAsJsonArray("args"); String kind = fixture.get("kind").getAsString(), name = fixture.get("name").getAsString();
            JsonElement result = JsonNull.INSTANCE; String error = null;
            try {
                if (kind.equals("inputs")) {
                    TradeState state = new TradeState("2026-10-01", 25000, args.get(10).getAsLong() - 1000, args.get(6).isJsonNull() ? null : args.get(6).getAsJsonObject());
                    result = ExecutionInputs.create(args.get(0).getAsString(), args.get(1).getAsJsonObject(), args.get(2).getAsJsonObject(), args.get(3).getAsJsonObject(), args.get(4).getAsJsonObject(), args.get(5).getAsJsonObject(), state, args.get(7).getAsJsonArray(), args.get(8).getAsJsonObject(), args.get(9).getAsJsonObject(), args.get(10).getAsLong(), args.get(11).getAsLong(), args.get(12).getAsJsonObject());
                } else if (kind.equals("store")) {
                    TradeState state = new TradeState(args.get(0).getAsString(), args.get(1).getAsDouble(), args.get(2).getAsLong(), args.get(3).isJsonNull() ? null : args.get(3).getAsJsonObject());
                    for (JsonElement item : fixture.getAsJsonArray("actions")) {
                        JsonArray action = item.getAsJsonArray(); String method = action.get(0).getAsString();
                        if (method.equals("acceptEntry")) state.acceptEntry(action.get(1).getAsString(), action.get(2).getAsJsonObject(), action.get(3).getAsJsonObject(), action.get(4).getAsLong());
                        else if (method.equals("updateCorePlan")) state.updateCorePlan(action.get(1).getAsString(), action.get(2).getAsBoolean(), action.get(3).getAsDouble(), action.get(4).getAsDouble());
                        else fail(method);
                    }
                    result = state.snapshot(); JsonObject detached = state.snapshot(); detached.addProperty("date", "mutated"); assertNotEquals("mutated", state.snapshot().get("date").getAsString());
                } else switch (kind + ":" + fixture.get("method").getAsString()) {
                    case "views:nativeViews": { JsonArray messages = new JsonArray(); NativeViews.project(args.get(0).getAsJsonObject()).forEach(messages::add); result = messages; break; }
                    case "views:positionRisk": result = new JsonPrimitive(NativeViews.positionRisk(args.get(0).getAsDouble(), args.get(1).getAsDouble(), args.get(2).getAsJsonArray(), args.get(3).getAsDouble(), args.get(4).getAsDouble())); break;
                    case "workflow:buyingPowerTargets": result = Workflows.buyingPowerTargets(args.get(0).getAsJsonArray(), args.get(1).getAsDouble(), args.get(2).getAsDouble()); break;
                    case "workflow:trailStopPrice": result = new JsonPrimitive(Workflows.trailStopPrice(args.get(0).getAsJsonArray(), args.get(1).getAsBoolean(), args.get(2).getAsInt(), args.get(3).getAsBoolean())); break;
                    case "workflow:firstVwapTouch": result = Workflows.firstVwapTouch(args.get(0).getAsJsonObject(), args.get(1).isJsonNull() ? null : args.get(1).getAsJsonObject(), args.get(2).getAsDouble(), args.get(3).getAsDouble()); break;
                    case "workflow:completedPartials": result = new JsonPrimitive(Workflows.completedPartials(args.get(0).getAsDouble(), args.get(1).getAsDouble(), args.get(2).getAsInt(), args.get(3).getAsDouble())); break;
                    case "workflow:profitResetTargets": result = Workflows.profitResetTargets(args.get(0).getAsJsonArray(), args.get(1).getAsDouble()); break;
                    case "workflow:stopDiscipline": result = Workflows.stopDiscipline(args.get(0).getAsString(), args.get(1).getAsDouble(), args.get(2).getAsDouble(), args.get(3).getAsBoolean(), args.get(4).getAsJsonArray(), args.get(5).getAsDouble(), args.get(6).getAsDouble()); break;
                    case "workflow:pendingStopRefresh": { JsonObject decision = Workflows.pendingStopRefresh(args.get(0).getAsJsonArray(), args.get(1).getAsInt(), args.get(2).getAsDouble(), args.get(3).getAsString(), args.get(4).getAsDouble(), args.get(5).getAsDouble()); result = decision == null ? JsonNull.INSTANCE : decision; break; }
                    case "ledger:projectTradeLedger": result = TradeLedger.projectTradeLedger(args.get(0).getAsJsonObject(), args.size() > 1 ? args.get(1).getAsDouble() : 4000); break;
                    case "ledger:addedPartialStack": result = TradeLedger.addedPartialStack(args.get(0).isJsonNull() ? null : args.get(0).getAsJsonObject(), args.get(1).getAsDouble()); break;
                    case "state:defaultBreakout": result = TradeState.defaultBreakout(args.get(0).getAsBoolean(), args.get(1).getAsLong()); break;
                    case "state:acceptedBreakout": result = TradeState.acceptedBreakout(args.get(0).getAsJsonObject(), args.get(1).getAsLong()); break;
                    case "config:validateTradingPlan": result = new JsonPrimitive(TradingConfig.validateTradingPlan(args.get(0).getAsJsonObject())); break;
                    case "config:createTradebookDefinitions": result = TradingConfig.createTradebookDefinitions(args.get(0).getAsJsonObject()); break;
                    case "config:readTradingConfig": result = TradingConfig.readTradingConfig(args.get(0).isJsonNull() ? null : args.get(0).getAsJsonObject(), args.size() > 1 ? args.get(1).getAsInt() : 1).toJson(); break;
                    default: fail(name);
                }
            } catch (Exception failure) { error = failure.getMessage(); }
            if (fixture.has("error")) assertEquals(fixture.get("error").getAsString(), error, name);
            else { assertNull(error, name); assertEquals(fixture.get("result"), result, name); }
        }
    }
}
