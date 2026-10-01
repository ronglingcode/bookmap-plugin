package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.ReadApi;
import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.AccountProjection;
import com.bookmap.plugin.rong.miniviteapp.ports.HttpPort;
import com.google.gson.*;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BrokerReadParityTest {
    @Test void agreesWithProductionTypeScript() throws Exception {
        JsonArray fixtures = JsonParser.parseReader(new InputStreamReader(getClass().getResourceAsStream("/broker-read-fixtures.json"), StandardCharsets.UTF_8)).getAsJsonArray();
        for (JsonElement value : fixtures) {
            JsonObject fixture = value.getAsJsonObject(); String name = fixture.get("name").getAsString();
            JsonArray args = fixture.getAsJsonArray("args"), requests = new JsonArray();
            HttpPort http = (uri, verb, headers, body) -> {
                JsonObject request = new JsonObject(); request.addProperty("path", uri.getPath()); JsonObject query = new JsonObject();
                if (uri.getRawQuery() != null) for (String item : uri.getRawQuery().split("&")) {
                    String[] pair = item.split("=", 2); query.addProperty(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
                }
                request.add("query", query); request.addProperty("method", verb); JsonObject headerJson = new JsonObject(); headers.forEach(headerJson::addProperty); request.add("headers", headerJson); requests.add(request);
                JsonArray pages = fixture.getAsJsonArray("pages"); if (requests.size() > pages.size()) throw new IllegalArgumentException("Unexpected extra read");
                JsonObject page = pages.get(requests.size() - 1).getAsJsonObject(); JsonElement pageBody = page.get("body");
                return new HttpPort.Response(page.has("status") ? page.get("status").getAsInt() : 200, pageBody.isJsonPrimitive() && pageBody.getAsJsonPrimitive().isString() ? pageBody.getAsString() : pageBody.toString());
            };
            JsonElement result = JsonNull.INSTANCE; String error = null;
            try {
                if (fixture.get("kind").getAsString().equals("projection")) result = AccountProjection.projectAccount(args.get(0).getAsJsonObject(), args.get(1).getAsJsonArray(), args.get(2).getAsString(), symbol -> 11);
                else {
                    ReadApi api = new ReadApi(http);
                    switch (fixture.get("method").getAsString()) {
                        case "getAccount": result = api.getAccount(args.get(0).getAsString()); break;
                        case "getStreamerInfo": result = api.getStreamerInfo(args.get(0).getAsString()); break;
                        case "getOrders": result = api.getOrders(args.get(0).getAsString(), args.get(1).getAsString(), args.get(2).getAsString(), args.size() > 3 && args.get(3).getAsBoolean()); break;
                        default: fail(name);
                    }
                }
            } catch (Exception failure) { error = failure.getMessage(); }
            if (fixture.has("error")) assertEquals(fixture.get("error").getAsString(), error, name);
            else { assertNull(error, name); assertEquals(fixture.get("result"), result, name); }
            if (fixture.has("requests")) assertEquals(fixture.get("requests"), requests, name);
        }
    }
}
