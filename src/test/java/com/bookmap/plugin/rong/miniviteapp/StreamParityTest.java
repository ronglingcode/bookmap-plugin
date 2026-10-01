package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.StreamingProtocol;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StreamParityTest {
    private static List<String> symbols(JsonElement value) { List<String> result = new ArrayList<>(); value.getAsJsonArray().forEach(symbol -> result.add(symbol.getAsString())); return result; }
    @Test void agreesWithProductionTypeScript() {
        JsonArray fixtures = JsonParser.parseReader(new InputStreamReader(getClass().getResourceAsStream("/stream-fixtures.json"), StandardCharsets.UTF_8)).getAsJsonArray();
        for (JsonElement value : fixtures) {
            JsonObject fixture = value.getAsJsonObject(); JsonArray args = fixture.getAsJsonArray("args"); JsonElement result = null;
            String kind = fixture.get("kind").getAsString(), method = fixture.get("method").getAsString();
            if (kind.equals("schwab")) switch (method) {
                case "loginRequest": result = StreamingProtocol.loginRequest(args.get(0).getAsJsonObject(), args.get(1).getAsString()); break;
                case "quoteSubscribeRequest": result = StreamingProtocol.quoteSubscribeRequest(args.get(0).getAsJsonObject(), symbols(args.get(1))); break;
                case "activitySubscribeRequest": result = StreamingProtocol.activitySubscribeRequest(args.get(0).getAsJsonObject()); break;
                case "mapQuote": result = StreamingProtocol.mapQuote(args.get(0).getAsJsonObject()); break;
                case "parseStreamMessage": result = StreamingProtocol.parseStreamMessage(args.get(0).getAsJsonObject()); break;
            }
            else switch (method) {
                case "loginRequest": result = com.bookmap.plugin.rong.miniviteapp.libraries.massive.StreamingProtocol.loginRequest(args.get(0).getAsString()); break;
                case "subscribeRequest": result = com.bookmap.plugin.rong.miniviteapp.libraries.massive.StreamingProtocol.subscribeRequest(symbols(args.get(0))); break;
                case "parseStreamMessage": result = com.bookmap.plugin.rong.miniviteapp.libraries.massive.StreamingProtocol.parseStreamMessage(args.get(0).getAsJsonArray()).toJson(); break;
            }
            assertNotNull(result); assertEquals(fixture.get("result"), result, fixture.get("name").getAsString());
        }
    }
}
