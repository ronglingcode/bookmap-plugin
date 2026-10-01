package com.bookmap.plugin.rong.miniviteapp;

import com.bookmap.plugin.rong.miniviteapp.libraries.broker.schwab.OAuth;
import com.bookmap.plugin.rong.miniviteapp.libraries.firestore.Api;
import com.bookmap.plugin.rong.miniviteapp.libraries.firestore.DocumentCodec;
import com.bookmap.plugin.rong.miniviteapp.libraries.firestore.LogRepository;
import com.bookmap.plugin.rong.miniviteapp.ports.CredentialPort;
import com.bookmap.plugin.rong.miniviteapp.ports.HttpPort;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServiceParityTest {
    @Test void agreesWithProductionTypeScript() throws Exception {
        JsonArray fixtures = JsonParser.parseReader(new InputStreamReader(
                getClass().getResourceAsStream("/service-fixtures.json"), StandardCharsets.UTF_8)).getAsJsonArray();
        for (JsonElement element : fixtures) {
            JsonObject fixture = element.getAsJsonObject();
            String name = fixture.get("name").getAsString(), kind = fixture.get("kind").getAsString(), method = fixture.get("method").getAsString();
            JsonArray args = fixture.getAsJsonArray("args"), requests = new JsonArray(), saved = new JsonArray();
            HttpPort http = (uri, verb, headers, body) -> {
                JsonObject request = new JsonObject(); request.addProperty("path", uri.getRawPath()); request.addProperty("method", verb);
                JsonObject headerJson = new JsonObject(); headers.forEach(headerJson::addProperty); request.add("headers", headerJson);
                if (body != null) request.add("body", kind.equals("oauth") ? new JsonPrimitive(body) : JsonParser.parseString(body));
                requests.add(request);
                JsonObject page = fixture.getAsJsonArray("pages").get(requests.size() - 1).getAsJsonObject();
                return new HttpPort.Response(page.get("status").getAsInt(), page.get("body").getAsString());
            };
            Api firestore = new Api(http, "test-project", () -> "test-firestore-key");
            LogRepository logs = new LogRepository(firestore, () -> "schwab", () -> java.time.Instant.parse("2026-10-01T13:30:00Z").toEpochMilli());
            AtomicReference<JsonObject> stored = new AtomicReference<>(fixture.has("credentials") ? fixture.getAsJsonObject("credentials").deepCopy() : new JsonObject());
            CredentialPort credentials = new CredentialPort() {
                public JsonObject loadSchwab() { return stored.get().deepCopy(); }
                public void saveSchwab(JsonObject value) { stored.set(value.deepCopy()); saved.add(value.deepCopy()); }
            };
            OAuth oauth = new OAuth(http, credentials, URI.create("https://api.schwabapi.com/v1/oauth/token"), () -> 100000);
            JsonElement result = JsonNull.INSTANCE; String error = null;
            try {
                switch (kind + ":" + method) {
                    case "codec:decodeFields": result = DocumentCodec.decodeFields(args.get(0).getAsJsonObject()); break;
                    case "codec:encodeFields": result = DocumentCodec.encodeFields(args.get(0).getAsJsonObject()); break;
                    case "firestore:fetchConfigData": result = firestore.fetchConfigData(); break;
                    case "firestore:getTradingState":
                        JsonObject state = firestore.getTradingState(args.get(0).getAsString()); result = state == null ? JsonNull.INSTANCE : state; break;
                    case "firestore:setTradingState": firestore.setTradingState(args.get(0).getAsString(), args.get(1).getAsJsonObject()); break;
                    case "firestore:addDocument": firestore.addDocument(args.get(0).getAsString(), args.get(1).getAsJsonObject()); break;
                    case "oauth:refresh": result = oauth.refresh(); break;
                    case "oauth:accessToken": result = new JsonPrimitive(oauth.accessToken(false)); break;
                    case "oauth:exchangeAuthorizationCode": result = oauth.exchangeAuthorizationCode(args.get(0).getAsString()); break;
                    case "log:log": logs.log(args.get(0).getAsString(), args.get(1), args.get(2).getAsJsonObject()); break;
                    case "log:logOrder": logs.logOrder(args.get(0), args.get(1).getAsJsonObject()); break;
                    case "log:logBreakoutTradeState": logs.logBreakoutTradeState(args.get(0).getAsString(), args.get(1).getAsJsonObject()); break;
                    default: fail("Unknown scenario " + kind + ":" + method);
                }
            } catch (Exception failure) { error = failure.getMessage(); }
            if (fixture.has("error")) assertEquals(fixture.get("error").getAsString(), error, name);
            else { assertNull(error, name); assertEquals(fixture.get("result"), result, name); }
            if (fixture.has("requests")) assertEquals(fixture.get("requests"), requests, name);
            if (fixture.has("saved")) assertEquals(fixture.get("saved"), saved, name);
        }
    }
}
