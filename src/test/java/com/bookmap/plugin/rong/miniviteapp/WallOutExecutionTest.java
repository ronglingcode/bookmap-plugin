package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.core.controllers.Handler;
import com.bookmap.plugin.rong.miniviteapp.models.Models.Snapshot;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WallOutExecutionTest {
    @Test void sharedFixturesMatchLiveBrowserHandlerIncludingLimitOnlyPayloadsAndRuleBlocks() {
        var fixtures = JsonParser.parseReader(new InputStreamReader(getClass()
                .getResourceAsStream("/wall-out-fixtures.json"), StandardCharsets.UTF_8)).getAsJsonArray();
        for (var element : fixtures) {
            var fixture = element.getAsJsonObject();
            var state = new Snapshot(fixture.getAsJsonObject("state"));
            String name = fixture.get("name").getAsString();
            int count = fixture.get("count").getAsInt();
            var snapshot = fixture.getAsJsonObject("snapshot");
            if (fixture.get("error").getAsBoolean()) {
                assertThrows(IllegalArgumentException.class, () -> Handler.wallOutPartials(state, count, snapshot), name);
            } else {
                var plan = Handler.wallOutPartials(state, count, snapshot);
                assertEquals(fixture.getAsJsonArray("requests"), plan.toJson(), name);
                assertTrue(plan.requests.stream().allMatch(request -> request.original.type.equals("LIMIT")), name);
            }
        }
    }
}
