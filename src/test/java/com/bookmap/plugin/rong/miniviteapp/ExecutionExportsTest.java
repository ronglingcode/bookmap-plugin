package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.account.ExecutionExports;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionExportsTest {
    @Test void nativeFormatsExactlyMatchProductionTypeScriptFixtures() {
        JsonArray fixtures = JsonParser.parseReader(new InputStreamReader(getClass().getResourceAsStream("/execution-export-fixtures.json"), StandardCharsets.UTF_8)).getAsJsonArray();
        for (JsonElement item : fixtures) {
            JsonObject fixture = item.getAsJsonObject(); JsonArray args = fixture.getAsJsonArray("args");
            String actual = fixture.get("method").getAsString().equals("executionScript")
                ? ExecutionExports.executionScript(args.get(0).getAsJsonArray(), args.get(1).getAsBoolean())
                : ExecutionExports.executionTradesCsv(args.get(0).getAsJsonArray(), args.get(1).getAsLong(), ZoneId.of(args.get(2).getAsString()));
            assertEquals(fixture.get("result").getAsString(), actual, fixture.get("name").getAsString());
        }
    }
}
