package com.bookmap.plugin.rong.miniviteapp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.bookmap.plugin.rong.miniviteapp.runtime.NativeAccountDiagnostics;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class NativeAccountDiagnosticsTest {

    @Test
    void logsFetchedOrdersAndBothSidesOfTheFillProjectionWithoutCredentials() {
        JsonArray orders = JsonParser.parseString("[{\"orderId\":42,\"status\":\"FILLED\","
                + "\"accessToken\":\"private-token\",\"orderLegCollection\":[{\"instrument\":{\"symbol\":\"NVDA\"}}],"
                + "\"orderActivityCollection\":[{\"activityType\":\"EXECUTION\",\"executionType\":\"FILL\","
                + "\"executionLegs\":[{\"time\":\"2026-10-02T13:30:00Z\",\"price\":236.8,\"quantity\":10}]}]}]")
                .getAsJsonArray();
        long timestamp = Instant.parse("2026-10-02T13:30:00Z").toEpochMilli();
        JsonObject account = JsonParser.parseString("{\"executions\":{\"NVDA\":[{\"orderID\":\"42\","
                + "\"isBuy\":true,\"positionEffectIsOpen\":true,\"price\":236.8,\"quantity\":10,"
                + "\"timestamp\":" + timestamp + "}]}}")
                .getAsJsonObject();

        String raw = String.join("\n", NativeAccountDiagnostics.rawOrders(orders, "2026-10-02"));
        String projected = String.join("\n", NativeAccountDiagnostics.projectedFills(account, "2026-10-02"));

        assertTrue(raw.contains("fetched=1 executionActivities=1 fillLegs=1 parseableFillLegs=1 todayFillLegs=1"));
        assertTrue(raw.contains("orderId=42 symbol=NVDA status=FILLED quantity=10 price=236.8"));
        assertTrue(raw.contains("Native broker execution time sample: orderId=42 symbol=NVDA raw=2026-10-02T13:30:00Z"));
        assertTrue(projected.contains("count=1"));
        assertTrue(projected.contains("symbol=NVDA orderId=42 side=BUY effect=OPENING quantity=10 price=236.8"));
        assertTrue(projected.contains("utc=2026-10-02T13:30:00Z"));
        assertTrue(projected.contains("pacific=2026-10-02 06:30:00 PDT"));
        assertTrue(projected.contains("eastern=2026-10-02 09:30:00 EDT"));
        assertFalse(raw.contains("private-token"));
    }

    @Test
    void samplesRawExecutionTimesEvenWhenTheDateParserRejectsThem() {
        JsonArray orders = JsonParser.parseString("[{\"orderId\":42,\"orderLegCollection\":[{\"instrument\":{\"symbol\":\"NVDA\"}}],"
                + "\"orderActivityCollection\":[{\"activityType\":\"EXECUTION\",\"executionType\":\"FILL\","
                + "\"executionLegs\":[{\"time\":\"invalid-time\",\"price\":236.8,\"quantity\":10}]}]}]")
                .getAsJsonArray();

        String raw = String.join("\n", NativeAccountDiagnostics.rawOrders(orders, "2026-10-02"));

        assertTrue(raw.contains("fillLegs=1 parseableFillLegs=0 todayFillLegs=0"));
        assertTrue(raw.contains("raw=invalid-time parsed=time=invalid"));
    }
}
