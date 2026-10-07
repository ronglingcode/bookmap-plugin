package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.algorithms.TakeProfit;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TakeProfitTest {
    private JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private void check(JsonArray targets, double[] prices, double[] quantities) {
        assertEquals(prices.length, targets.size());
        double total = 0;
        for (int i = 0; i < prices.length; i++) {
            assertEquals(prices[i], targets.get(i).getAsJsonObject().get("target").getAsDouble());
            double quantity = targets.get(i).getAsJsonObject().get("quantity").getAsDouble();
            assertEquals(quantities[i], quantity); total += quantity;
        }
        assertEquals(java.util.Arrays.stream(quantities).sum(), total);
    }
    @Test void longUsesSixLevelsAndFortyPercentAtThreeR() {
        var walls = json("{\"effectiveWallThreshold\":5000,\"largeAsks\":[[99,9000],[100.5,4999],[104,5000],[102,6000],[101,7000],[101,8000],[105,9000]]}");
        var market = json("{\"vwap\":100.75,\"premarketHigh\":102.5}");
        check(TakeProfit.getEntryProfitTargets(1000, 100, 99, true, walls, 10, market),
                new double[]{100.75,101,102,102.5,104,103,103,103,103,103},
                new double[]{100,100,100,100,100,100,100,100,100,100});
        // 2R coincides with the second wall, so only five unique levels remain.
        market.addProperty("premarketHigh", 102.75);
        walls.getAsJsonArray("largeAsks").get(3).getAsJsonArray().set(0, new JsonPrimitive(101.5));
        check(TakeProfit.getEntryProfitTargets(1000,100,99,true,walls,10,market),
                new double[]{100.75,101,101.5,102,102.75,104,103,103,103,103},
                new double[]{100,100,100,100,100,100,100,100,100,100});
    }
    @Test void shortFiltersPassedLevelsAndUsesBids() {
        var walls = json("{\"effectiveWallThreshold\":5000,\"largeBids\":[[100,9000],[101,9000],[99.5,5000],[99,4999],[98.5,6000]],\"largeAsks\":[[99.75,9000]]}");
        check(TakeProfit.getEntryProfitTargets(1000,100,101,false,walls,10,json("{\"vwap\":101,\"premarketLow\":97.5}")),
                new double[]{99.5,98.5,98,97.5,97,97,97,97,97,97},
                new double[]{100,100,100,100,100,100,100,100,100,100});
    }
    @Test void missingLevelsLeaveTwoRAndThreeRAndConserveOddShareCount() {
        check(TakeProfit.getEntryProfitTargets(103,100,99,true,null,10,new JsonObject()),
                new double[]{102,103,103,103,103,103,103,103,103,103},
                new double[]{10,11,11,11,10,10,10,10,10,10});
    }
    @Test void centRoundingFiltersLevelsAtEntryAndDeduplicates() {
        check(TakeProfit.getEntryProfitTargets(100,100,99,true,null,10,json("{\"vwap\":100.004,\"premarketHigh\":102.004}")),
                new double[]{102,103,103,103,103,103,103,103,103,103},
                new double[]{10,10,10,10,10,10,10,10,10,10});
    }
    @Test void smallEntriesKeepTheirBracketCountAndThreeRRemainder() {
        check(TakeProfit.getEntryProfitTargets(100,100,99,true,null,1,new JsonObject()), new double[]{103},new double[]{100});
        check(TakeProfit.getEntryProfitTargets(100,100,99,true,null,5,new JsonObject()),
                new double[]{102,103,103,103,103},new double[]{10,23,23,22,22});
    }
}
