package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.marketdata.Levels;
import com.bookmap.plugin.rong.miniviteapp.core.marketdata.Eligibility;
import com.bookmap.plugin.rong.miniviteapp.core.marketdata.Liquidity;
import com.bookmap.plugin.rong.miniviteapp.core.marketdata.MarketState;
import com.bookmap.plugin.rong.miniviteapp.libraries.massive.Mapper;
import com.bookmap.plugin.rong.miniviteapp.models.Candle;
import com.bookmap.plugin.rong.miniviteapp.models.Trade;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketParityTest {
    @Test void agreesWithProductionTypeScript() {
        JsonArray fixtures = JsonParser.parseReader(new InputStreamReader(getClass().getResourceAsStream("/market-fixtures.json"), StandardCharsets.UTF_8)).getAsJsonArray();
        for (JsonElement element : fixtures) {
            JsonObject fixture = element.getAsJsonObject(); String name = fixture.get("name").getAsString(); JsonElement result = null;
            switch (fixture.get("kind").getAsString()) {
                case "state":
                    MarketState state = new MarketState("AAPL", "2026-10-01", fixture.get("marketCap").getAsDouble());
                    List<Candle> history = new ArrayList<>();
                    fixture.getAsJsonArray("history").forEach(value -> { JsonObject c = value.getAsJsonObject(); history.add(new Candle(c.get("symbol").getAsString(), c.get("datetime").getAsLong(), c.get("open").getAsDouble(), c.get("high").getAsDouble(), c.get("low").getAsDouble(), c.get("close").getAsDouble(), c.get("volume").getAsDouble(), c.get("vwap").getAsDouble())); });
                    JsonObject correction = fixture.has("correction") ? fixture.getAsJsonObject("correction") : new JsonObject();
                    state.initialize(history, fixture.get("liveFrom").getAsLong(), correction.has("volumeSum") ? correction.get("volumeSum").getAsDouble() : 0, correction.has("tradingSum") ? correction.get("tradingSum").getAsDouble() : 0);
                    assertEquals(fixture.get("initial"), state.snapshot(), name + " initial");
                    JsonArray accepted = new JsonArray();
                    fixture.getAsJsonArray("trades").forEach(value -> { JsonObject t = value.getAsJsonObject(); accepted.add(state.applyTrade(new Trade(t.get("symbol").getAsString(), t.get("timestamp").getAsLong(), t.get("price").getAsDouble(), t.get("size").getAsDouble(), t.has("sequence") ? t.get("sequence").getAsString() : null, null, null, List.of()))); });
                    assertEquals(fixture.get("accepted"), accepted, name + " accepted"); result = state.snapshot(); break;
                case "liquidity":
                    JsonArray args = fixture.getAsJsonArray("args"); List<Double> volumes = new ArrayList<>(); args.get(1).getAsJsonArray().forEach(value -> volumes.add(value.getAsDouble()));
                    result = new JsonPrimitive(Liquidity.calculateLiquidityScale(args.get(0).getAsDouble(), volumes, args.get(2).getAsDouble(), args.get(3).getAsDouble(), args.get(4).getAsBoolean())); break;
                case "levels":
                    JsonArray levelArgs = fixture.getAsJsonArray("args"); result = Levels.calculateCamPivots(levelArgs.get(0).getAsDouble(), levelArgs.get(1).getAsDouble(), levelArgs.get(2).getAsDouble()); break;
                case "mapper":
                    Trade mapped = Mapper.mapWebSocketTrade(fixture.getAsJsonObject("input")); result = mapped.toJson(); assertEquals(fixture.get("filtered").getAsBoolean(), Mapper.shouldFilterTrade(mapped), name); break;
                case "premarketEligibility":
                    JsonArray volumeArgs = fixture.getAsJsonArray("args"); result = Eligibility.premarketEligibility(volumeArgs.get(0).getAsDouble(), volumeArgs.get(1).getAsDouble(), volumeArgs.get(2).getAsDouble(), volumeArgs.get(3).getAsDouble(), volumeArgs.get(4).getAsDouble()); break;
                case "marketCap":
                    JsonArray capArgs = fixture.getAsJsonArray("args"); result = new JsonPrimitive(Eligibility.impliedMarketCapInBillions(capArgs.get(0).getAsDouble(), capArgs.get(1).getAsDouble())); break;
                case "consolidation":
                    List<Candle> daily = new ArrayList<>();
                    fixture.getAsJsonArray("candles").forEach(value -> { JsonObject c = value.getAsJsonObject(); daily.add(new Candle(c.get("symbol").getAsString(), c.get("datetime").getAsLong(), c.get("open").getAsDouble(), c.get("high").getAsDouble(), c.get("low").getAsDouble(), c.get("close").getAsDouble(), c.get("volume").getAsDouble(), c.get("vwap").getAsDouble())); });
                    result = new JsonPrimitive(Eligibility.validatePreviousConsolidationArea(fixture.get("area").isJsonNull() ? null : fixture.getAsJsonObject("area"), daily)); break;
                default: fail("Unknown market scenario " + name);
            }
            assertEquals(fixture.get("result"), result, name);
        }
    }
}
